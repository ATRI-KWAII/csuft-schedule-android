package cn.csuft.schedulelite;

import org.json.JSONObject;

public final class WeatherClientTest {
    public static void main(String[] args) throws Exception {
        String text = WeatherClient.parse(new JSONObject("{current:{temperature_2m:13.9,weather_code:3}}"));
        if (!text.contains("13.9°C") || !text.contains("阴") || !text.contains("固定地点"))
            throw new AssertionError("Current temperature, condition, and location must be shown");
        if (!WeatherClient.condition(95).equals("雷雨")) throw new AssertionError("Thunderstorm code");
        try {
            WeatherClient.parse(new JSONObject("{current:{weather_code:0}}"));
            throw new AssertionError("Missing temperature must fail");
        } catch (org.json.JSONException expected) { }
        System.out.println("PASS: weather response parsing and missing data");
    }
}
