package cn.csuft.schedulelite;

import android.os.Build;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;

class ApiClient {
    private static final String TOKEN_BASE = "https://cas.csuft.edu.cn/token";
    private static final String PORTAL_BASE = "https://portal.csuft.edu.cn/portal-api";
    private static final int TIMEOUT = 20000;

    private final String deviceId;
    private String token;

    ApiClient(String deviceId, String token) {
        this.deviceId = deviceId == null ? "" : deviceId;
        this.token = token == null ? "" : token;
    }

    void setToken(String value) {
        token = value == null ? "" : value;
    }

    LoginResult login(String username, String password) throws Exception {
        JSONObject config = getJson(TOKEN_BASE + "/login/configs", false);
        JSONObject loginConfig = checkedData(config);
        JSONObject pageConfig = loginConfig.optJSONObject("loginPageConfig");
        if (pageConfig == null) throw new ApiException("登录配置缺失，请稍后重试", 0);
        boolean encrypt = pageConfig.optBoolean("encryptEnabled", true);

        String mfaState = "";
        JSONObject detect = postForm(TOKEN_BASE + "/mfa/detect",
                pair("username", username, "deviceId", deviceId), false);
        JSONObject detection = checkedData(detect);
        mfaState = detection.optString("state", "");

        String loginName = username;
        String loginPassword = password;
        if (encrypt) {
            Object keyValue = getValue(TOKEN_BASE + "/jwt/publicKey", false);
            String publicKey = keyValue instanceof String ? (String) keyValue : String.valueOf(keyValue);
            loginName = rsa(publicKey, username);
            loginPassword = rsa(publicKey, password);
        }

        List<String[]> fields = pair(
                "username", loginName,
                "password", loginPassword,
                "appId", "APP_ID",
                "deviceId", deviceId,
                "osType", "android",
                "geo", "",
                "mfaState", mfaState);
        if (detection.optBoolean("mfaEnabled") && detection.optBoolean("need")) {
            throw new MfaRequired(detection, fields);
        }
        return finishPassword(fields);
    }

    private LoginResult finishPassword(List<String[]> fields) throws Exception {
        JSONObject response = postEmpty(TOKEN_BASE + "/password/passwordLogin?" + query(fields), false);
        return parseLoginResponse(response);
    }

    String sendSmsLogin(String mobile) throws Exception {
        JSONObject config = checkedData(getJson(TOKEN_BASE + "/login/configs", false));
        if (!objectValue(config.opt("loginModeConfig")).optBoolean("authenticationSmsCodeEnabled"))
            throw new ApiException("学校当前未开放短信验证码登录", 0);
        String nonce = required(checkedData(getJson(TOKEN_BASE + "/passwordless/smsInit", false)), "nonce");
        JSONObject sent = postEmpty(TOKEN_BASE + "/passwordless/smsSend?" + query(pair("mobile", mobile, "nonce", nonce)), false);
        // smsSend rotates the nonce. Login must use the returned value, not smsInit's value.
        return required(checkedData(sent), "nonce");
    }

    LoginResult smsLogin(String mobile, String code, String nonce) throws Exception {
        return parseLoginResponse(postEmpty(TOKEN_BASE + "/passwordless/smsLogin?" + query(pair(
                "mobile", mobile, "smscode", code, "nonce", nonce, "appId", "APP_ID",
                "deviceId", deviceId, "osType", "android", "geo", "")), false));
    }

    JSONObject initMfa(MfaRequired pending, String type) throws Exception {
        if (!type.equals("securephone") && !type.equals("secureemail")) throw new ApiException("不支持的验证方式", 0);
        return checkedData(getJson(TOKEN_BASE + "/mfa/initByType/" + type + "?" + query(pair("state", required(pending.detection, "state"))), false));
    }

    void sendMfa(JSONObject challenge, String type) throws Exception {
        JSONObject data = guard(challenge, type, "send", new JSONObject().put("gid", required(challenge, "gid")));
        if (!"ok".equals(data.optString("result"))) throw new ApiException("验证会话已过期，请关闭弹窗后重新登录", 0);
    }

    LoginResult completeMfa(MfaRequired pending, JSONObject challenge, String type, String code) throws Exception {
        JSONObject data = guard(challenge, type, "valid", new JSONObject().put("gid", required(challenge, "gid")).put("code", code));
        if (!"ok".equals(data.optString("result")) || data.optInt("status") == 9)
            throw new ApiException("验证会话已过期，请关闭弹窗后重新登录", 0);
        if (data.optInt("status") != 2) throw new ApiException("验证码未通过，请检查后重新输入", 0);
        // Continue the original login with the original mfaState; do not start a new detection.
        return finishPassword(pending.fields);
    }

    private JSONObject guard(JSONObject challenge, String type, String action, JSONObject body) throws Exception {
        URL base = new URL(required(challenge, "attestServerUrl"));
        if (!"https".equals(base.getProtocol())) throw new ApiException("认证服务未提供 HTTPS 地址", 0);
        Object response = request("POST", trimSlash(base.toString()) + "/api/guard/" + type + "/" + action,
                body.toString().getBytes(StandardCharsets.UTF_8), "application/json", false);
        if (!(response instanceof JSONObject)) throw new ApiException("认证服务响应格式错误", 0);
        return checkedData((JSONObject) response);
    }

    static JSONObject checkedData(JSONObject response) throws ApiException {
        if (response.optInt("code", -1) != 0) throw new ApiException(message(response, "认证请求失败"), response.optInt("code", -1));
        JSONObject data = response.optJSONObject("data");
        if (data == null) throw new ApiException("认证响应缺少 data", 0);
        return data;
    }

    private static String required(JSONObject object, String key) throws ApiException {
        String value = object.optString(key, "");
        if (value.isEmpty() || "null".equals(value)) throw new ApiException("认证响应缺少 " + key, 0);
        return value;
    }

    static final class MfaRequired extends ApiException {
        final JSONObject detection;
        final List<String[]> fields;
        MfaRequired(JSONObject detection, List<String[]> fields) {
            super("请完成二次验证", 0);
            this.detection = detection;
            this.fields = fields;
        }
        void clear() { fields.clear(); }
    }

    LoginResult chooseAccount(String accountId, String cid) throws Exception {
        String url = TOKEN_BASE + "/login/accountChoose?" + query(pair(
                "accountId", accountId, "cid", cid));
        return parseLoginResponse(postEmpty(url, false));
    }

    private LoginResult parseLoginResponse(JSONObject response) throws ApiException {
        int code = response.optInt("code", -1);
        JSONObject data = response.optJSONObject("data");
        if (code == 0 && data != null && !data.optString("idToken").isEmpty()) {
            token = data.optString("idToken");
            return LoginResult.token(token);
        }
        if (code == 100000 && data != null && data.optJSONArray("accounts") != null) {
            return LoginResult.accounts(data.optJSONArray("accounts"), data.optString("cid"));
        }
        throw new ApiException(message(response, "登录失败"), code);
    }

    CourseBundle loadWeek(String startDate, String endDate) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        Calendar requestStart = Calendar.getInstance();
        requestStart.setTime(format.parse(startDate));
        requestStart.add(Calendar.DAY_OF_MONTH, -1);
        Calendar requestEnd = Calendar.getInstance();
        requestEnd.setTime(format.parse(endDate));
        requestEnd.add(Calendar.DAY_OF_MONTH, 1);
        String url = PORTAL_BASE + "/v1/calendar/share/schedule/getEvents?" + query(pair(
                "startDate", format.format(requestStart.getTime()), "endDate", format.format(requestEnd.getTime()), "reqType", "DateView",
                "random_number", String.valueOf(System.currentTimeMillis())));
        JSONObject response = getJson(url, true);
        JSONObject data = objectValue(response.opt("data"));
        JSONObject schedule = objectValue(data.opt("schedule"));
        ArrayList<Course> courses = new ArrayList<Course>();
        ArrayList<CourseDay> days = new ArrayList<CourseDay>();
        ArrayList<String> sourceNames = new ArrayList<String>();
        Calendar cursor = Calendar.getInstance();
        cursor.setTime(format.parse(startDate));
        for (int dayIndex = 0; dayIndex < 7; dayIndex++) {
            String date = format.format(cursor.getTime());
            JSONObject day = objectValue(schedule.opt(date));
            JSONArray items = arrayValue(day.opt("calendarList"));
            ArrayList<Course> dayCourses = new ArrayList<Course>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String sourceName = item.optString("calendarName", "课程表");
                if (!sourceNames.contains(sourceName)) sourceNames.add(sourceName);
                Course course = Course.from(item, sourceName);
                dayCourses.add(course);
                courses.add(course);
            }
            days.add(new CourseDay(date, dayCourses));
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
        return new CourseBundle(PORTAL_BASE, "", courses, days, sourceNames);
    }

    private JSONObject getJson(String url, boolean auth) throws Exception {
        Object value = request("GET", url, null, null, auth);
        if (!(value instanceof JSONObject)) throw new ApiException("接口返回的不是 JSON 对象：" + url, 0);
        JSONObject json = (JSONObject) value;
        if (auth && json.has("code") && json.optInt("code", 0) != 0) {
            throw new ApiException(message(json, "请求失败"), json.optInt("code", 0));
        }
        return json;
    }

    private Object getValue(String url, boolean auth) throws Exception {
        return request("GET", url, null, null, auth);
    }

    private JSONObject postForm(String url, List<String[]> fields, boolean auth) throws Exception {
        Object value = request("POST", url, query(fields).getBytes(StandardCharsets.UTF_8),
                "application/x-www-form-urlencoded", auth);
        if (!(value instanceof JSONObject)) throw new ApiException("接口返回格式错误", 0);
        return (JSONObject) value;
    }

    private JSONObject postEmpty(String url, boolean auth) throws Exception {
        Object value = request("POST", url, new byte[0], "application/x-www-form-urlencoded", auth);
        if (!(value instanceof JSONObject)) throw new ApiException("接口返回格式错误", 0);
        return (JSONObject) value;
    }

    Object request(String method, String url, byte[] body, String contentType, boolean auth) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Language", "zh-CN");
        connection.setRequestProperty("X-Terminal-Info", "app");
        connection.setRequestProperty("X-Device-Info", Build.BRAND + Build.MODEL + Build.VERSION.RELEASE);
        connection.setRequestProperty("X-Device-Infos", "{\"packagename\":\"cn.csuft.schedulelite\",\"version\":\"1.0\",\"system\":\"android\"}");
        if (auth) connection.setRequestProperty("X-Id-Token", token);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType == null ? "application/x-www-form-urlencoded" : contentType);
            OutputStream output = connection.getOutputStream();
            output.write(body);
            output.close();
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = read(stream);
        connection.disconnect();
        if (status == 401 || status == 403) throw new TokenException("登录状态已失效，请重新登录。");
        if (status < 200 || status >= 300) throw new ApiException("HTTP " + status + "：" + shortText(text), status);
        try {
            return new JSONTokener(text).nextValue();
        } catch (JSONException error) {
            throw new ApiException("接口返回无法解析：" + shortText(text), 0);
        }
    }

    private static String rsa(String pem, String value) throws Exception {
        String clean = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.decode(clean, Base64.DEFAULT)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return "__RSA__" + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String findStudyBase(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.has("key") && object.has("value")) {
                String key = object.optString("key").toUpperCase(Locale.ROOT);
                if (key.contains("STUDY_CENTER") || key.contains("STUDYCENTER")) return object.optString("value");
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.toUpperCase(Locale.ROOT).contains("STUDY_CENTER")) return String.valueOf(object.opt(key));
                String found = findStudyBase(object.opt(key));
                if (!found.isEmpty()) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String found = findStudyBase(array.opt(i));
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static JSONObject dataObject(JSONObject response) {
        Object data = response.opt("data");
        return objectValue(data);
    }

    private static JSONObject objectValue(Object value) {
        if (value instanceof JSONObject) return (JSONObject) value;
        if (value instanceof String) {
            try {
                Object parsed = new JSONTokener((String) value).nextValue();
                if (parsed instanceof JSONObject) return (JSONObject) parsed;
            } catch (Exception ignored) { }
        }
        return new JSONObject();
    }

    private static JSONArray arrayValue(Object value) {
        if (value instanceof JSONArray) return (JSONArray) value;
        if (value instanceof String) {
            try {
                Object parsed = new JSONTokener((String) value).nextValue();
                if (parsed instanceof JSONArray) return (JSONArray) parsed;
            } catch (Exception ignored) { }
        }
        return new JSONArray();
    }

    private static boolean contains(Object value, String target) {
        JSONArray array = arrayValue(value);
        for (int i = 0; i < array.length(); i++) if (target.equals(array.optString(i))) return true;
        return false;
    }

    private static List<String[]> pair(String... values) {
        ArrayList<String[]> result = new ArrayList<String[]>();
        for (int i = 0; i + 1 < values.length; i += 2) result.add(new String[]{values[i], values[i + 1]});
        return result;
    }

    private static String query(List<String[]> fields) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (String[] field : fields) {
            if (builder.length() > 0) builder.append('&');
            builder.append(URLEncoder.encode(field[0], "UTF-8"));
            builder.append('=');
            builder.append(URLEncoder.encode(field[1] == null ? "" : field[1], "UTF-8"));
        }
        return builder.toString();
    }

    private static String encodePath(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) builder.append(line);
        reader.close();
        return builder.toString();
    }

    private static String trimSlash(String value) {
        if (value == null) return "";
        value = value.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String message(JSONObject object, String fallback) {
        String value = object.optString("message", "");
        if (value.isEmpty()) value = object.optString("msg", "");
        return value.isEmpty() ? fallback : value;
    }

    private static String shortText(String text) {
        if (text == null) return "";
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.length() > 180 ? text.substring(0, 180) : text;
    }

    static final class LoginResult {
        final String token;
        final JSONArray accounts;
        final String cid;

        private LoginResult(String token, JSONArray accounts, String cid) {
            this.token = token;
            this.accounts = accounts;
            this.cid = cid;
        }

        static LoginResult token(String token) { return new LoginResult(token, null, ""); }
        static LoginResult accounts(JSONArray accounts, String cid) { return new LoginResult("", accounts, cid); }
    }

    static final class CourseBundle {
        final String studyBase;
        final String userName;
        final ArrayList<Course> courses;
        final ArrayList<CourseDay> days;
        final ArrayList<String> sourceNames;

        CourseBundle(String studyBase, String userName, ArrayList<Course> courses,
                     ArrayList<CourseDay> days, ArrayList<String> sourceNames) {
            this.studyBase = studyBase;
            this.userName = userName;
            this.courses = courses;
            this.days = days;
            this.sourceNames = sourceNames;
        }
    }

    static final class CourseDay {
        final String date;
        final ArrayList<Course> courses;

        CourseDay(String date, ArrayList<Course> courses) {
            this.date = date;
            this.courses = courses;
        }
    }

    static final class Course {
        final String name;
        final String project;
        final String room;
        final String teacher;
        final String time;
        final String source;

        Course(String name, String project, String room, String teacher, String time, String source) {
            this.name = name;
            this.project = project;
            this.room = room;
            this.teacher = teacher;
            this.time = time;
            this.source = source;
        }

        static Course from(JSONObject item, String source) {
            String name = first(item, "title", "name", "courseName", "KCMC", "kcmc");
            String project = first(item, "project", "courseType", "typeName");
            String room = first(item, "address", "roomString", "classroom", "room", "JASMC");
            String teacher = first(item, "teacherString", "teacherName", "teacher", "JSXM");
            if (teacher.isEmpty()) teacher = remarkValue(item.optString("remark", ""), "任课教师");
            String start = first(item, "startUnit", "startSection", "startTime");
            String end = first(item, "endUnit", "endSection", "endTime");
            String time = start;
            if (!end.isEmpty() && !end.equals(start)) time = start + " - " + end;
            if (name.isEmpty()) name = "未命名课程";
            return new Course(name, project, room, teacher, time, source);
        }

        private static String remarkValue(String remark, String label) {
            int start = remark.indexOf(label + ":");
            if (start < 0) start = remark.indexOf(label + "：");
            if (start < 0) return "";
            start += label.length() + 1;
            int end = remark.indexOf(';', start);
            int chineseEnd = remark.indexOf('；', start);
            if (end < 0 || chineseEnd >= 0 && chineseEnd < end) end = chineseEnd;
            return (end < 0 ? remark.substring(start) : remark.substring(start, end)).trim();
        }

        private static String first(JSONObject object, String... keys) {
            for (String key : keys) {
                String value = object.optString(key, "").trim();
                if (!value.isEmpty() && !"null".equals(value)) return value;
            }
            return "";
        }
    }

    static class ApiException extends Exception {
        final int code;
        ApiException(String message, int code) { super(message); this.code = code; }
    }

    static final class TokenException extends ApiException {
        TokenException(String message) { super(message, 401); }
    }
}
