package cn.csuft.schedulelite;

import org.json.JSONObject;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Offline response fixtures only. Never contacts the school or sends SMS. */
public class AuthFlowTest {
    static class Fake extends ApiClient {
        final Queue<JSONObject> responses = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        Fake() { super("test-device", ""); }
        void add(String json) throws Exception { responses.add(new JSONObject(json)); }
        @Override Object request(String method, String url, byte[] body, String contentType, boolean auth) {
            calls.add(method + " " + url + " " + (body == null ? "" : new String(body, StandardCharsets.UTF_8)));
            if (responses.isEmpty()) throw new AssertionError("Unexpected request " + url);
            return responses.remove();
        }
    }
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static Fake passwordStart() throws Exception {
        Fake f = new Fake();
        f.add("{code:0,data:{loginPageConfig:{encryptEnabled:false}}}");
        f.add("{code:0,data:{state:'original-state',mfaEnabled:true,need:true,mfaTypeSecurePhone:true,mfaTypeSecureEmail:true}}");
        return f;
    }
    static ApiClient.MfaRequired pending(Fake f) throws Exception {
        try { f.login("student", "fixture-password"); throw new AssertionError("MFA must interrupt login"); }
        catch (ApiClient.MfaRequired p) { check(f.calls.size()==2,"Login sent before MFA"); return p; }
    }
    public static void main(String[] args) throws Exception {
        Fake sms = new Fake();
        sms.add("{code:0,data:{loginModeConfig:{authenticationSmsCodeEnabled:true}}}");
        sms.add("{code:0,data:{nonce:'initial'}}");
        sms.add("{code:0,data:{nonce:'rotated'}}");
        String nonce = sms.sendSmsLogin("13800000000");
        check(nonce.equals("rotated"),"Must use rotated nonce");
        sms.add("{code:100000,data:{accounts:[{id:1},{id:2}],cid:'select-id'}}");
        ApiClient.LoginResult accounts = sms.smsLogin("13800000000","123456",nonce);
        check(accounts.accounts.length()==2 && accounts.cid.equals("select-id"),"SMS account picker");
        check(sms.calls.get(3).contains("nonce=rotated"),"Rotated nonce in actual request");
        sms.add("{code:0,data:{idToken:'fixture-token'}}");
        check(sms.chooseAccount("2",accounts.cid).token.equals("fixture-token"),"Chosen account token");

        Fake f=passwordStart(); ApiClient.MfaRequired p=pending(f);
        f.add("{code:0,data:{attestServerUrl:'https://verify.example',gid:'guard-id',securePhone:'138****0000'}}");
        JSONObject c=f.initMfa(p,"securephone");
        f.add("{code:0,data:{result:'ok'}}"); f.sendMfa(c,"securephone");
        check(f.calls.get(3).contains("/api/guard/securephone/send"),"Guard phone route");
        f.add("{code:0,data:{result:'ok',status:3}}");
        try { f.completeMfa(p,c,"securephone","wrong"); throw new AssertionError("Wrong OTP accepted"); }
        catch(ApiClient.ApiException expected) {}
        check(f.calls.size()==5,"No password submission on wrong OTP");
        f.add("{code:0,data:{result:'expired',status:9}}");
        try { f.completeMfa(p,c,"securephone","123456"); throw new AssertionError("Expired OTP accepted"); }
        catch(ApiClient.ApiException expected) {}
        f.add("{code:0,data:{result:'ok',status:2}}");
        f.add("{code:0,data:{idToken:'after-mfa'}}");
        check(f.completeMfa(p,c,"securephone","123456").token.equals("after-mfa"),"MFA continuation");
        check(f.calls.get(7).contains("mfaState=original-state"),"Preserve original MFA state");
        p.clear(); check(p.fields.isEmpty(),"Clear pending credentials");

        Fake fail = new Fake();
        fail.add("{code:0,data:{loginPageConfig:{encryptEnabled:false}}}");
        fail.add("{code:400,message:'detect failed'}");
        try { fail.login("student","secret"); throw new AssertionError("Detection failure ignored"); }
        catch(ApiClient.ApiException expected) {}
        check(fail.calls.size()==2,"Fail closed on detection error");
        Fake mail=passwordStart(); ApiClient.MfaRequired mp=pending(mail);
        mail.add("{code:0,data:{attestServerUrl:'https://verify.example',gid:'mail-id'}}");
        JSONObject mc=mail.initMfa(mp,"secureemail");
        mail.add("{code:0,data:{result:'ok'}}"); mail.sendMfa(mc,"secureemail");
        check(mail.calls.get(3).contains("/secureemail/send"),"Email route");

        Fake calendar = new Fake();
        calendar.add("{code:0,data:{schedule:{'2026-11-09':{calendarList:[{title:'数据结构',address:'诚意楼102',startTime:'08:00:00',endTime:'09:40:00',calendarName:'课程表',remark:'任课教师:杨老师;节次:1-2节'}]}}}}}");
        ApiClient.CourseBundle bundle = calendar.loadWeek("2026-11-09","2026-11-15");
        check(bundle.courses.size()==1,"Portal calendar course count");
        check(bundle.days.size()==7 && bundle.days.get(0).courses.size()==1,"Seven-day grouping");
        ApiClient.Course course = bundle.courses.get(0);
        check(course.name.equals("数据结构") && course.room.equals("诚意楼102"),"Portal calendar fields");
        check(course.teacher.equals("杨老师") && course.time.equals("08:00:00 - 09:40:00"),"Portal teacher and time");
        check(calendar.calls.get(0).contains("startDate=2026-11-08") && calendar.calls.get(0).contains("endDate=2026-11-16"),"Expanded weekly date range");
        System.out.println("PASS: auth flows and seven-day portal calendar parsing");
    }
}
