package cn.csuft.schedulelite;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Current model-based weather near the Changsha campus; no device location is collected. */
final class WeatherClient {
    private static final String URL_TEXT = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=28.145&longitude=112.997&current=temperature_2m%2Cweather_code"
            + "&timezone=Asia%2FShanghai&forecast_days=1";

    static String fetch() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(URL_TEXT).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setUseCaches(false);
        try {
            if (connection.getResponseCode() != 200) throw new Exception("天气服务暂不可用");
            try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    bytes.write(buffer, 0, count);
                    if (bytes.size() > 65536) throw new Exception("天气响应过大");
                }
                return parse(new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
            }
        } finally {
            connection.disconnect();
        }
    }

    static String parse(JSONObject root) throws Exception {
        JSONObject current = root.getJSONObject("current");
        double temperature = current.getDouble("temperature_2m");
        int code = current.getInt("weather_code");
        if (Double.isNaN(temperature) || Double.isInfinite(temperature)) throw new Exception("无气温数据");
        return String.format(Locale.CHINA, "长沙校区附近（固定地点）  %s  %.1f°C", condition(code), temperature);
    }

    static String condition(int code) {
        if (code == 0) return "晴";
        if (code == 1 || code == 2) return "多云";
        if (code == 3) return "阴";
        if (code == 45 || code == 48) return "雾";
        if (code >= 51 && code <= 57) return "毛毛雨";
        if (code >= 61 && code <= 67) return "雨";
        if (code >= 71 && code <= 77) return "雪";
        if (code >= 80 && code <= 82) return "阵雨";
        if (code == 85 || code == 86) return "阵雪";
        if (code >= 95 && code <= 99) return "雷雨";
        return "天气未知";
    }
}
