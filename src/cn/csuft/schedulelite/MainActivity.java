package cn.csuft.schedulelite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String PREFS = "schedule_prefs";
    private static final String KEY_TOKEN = "id_token";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService weatherExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences preferences;
    private ApiClient api;
    private Calendar selectedDate = Calendar.getInstance();
    private LinearLayout root;
    private View calendarPanel;
    private View weekPanel;
    private LinearLayout calendarContent;
    private TextView calendarStatus;
    private TextView weekStatus;
    private TextView weekRangeLabel;
    private TextView calendarNav;
    private TextView weekNav;
    private ProgressBar weekProgress;
    private Button calendarRefresh;
    private Button weekRefresh;
    private WeekScheduleView weekScheduleView;
    private ApiClient.CourseBundle currentBundle;
    private boolean showingWeek = true;
    private int requestVersion;
    private int weatherVersion;
    private TextView weekWeather;
    private TextView calendarWeather;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.GREEN_DEEP);
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().setNavigationBarColor(Color.WHITE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        api = new ApiClient(androidId, preferences.getString(KEY_TOKEN, ""));
        if (preferences.getString(KEY_TOKEN, "").isEmpty()) showLogin();
        else showSchedule(true);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        weatherExecutor.shutdownNow();
        super.onDestroy();
    }

    private void showLogin() {
        requestVersion++;
        weatherVersion++;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout panel = column(0);
        panel.setBackgroundColor(Ui.BG);
        scroll.addView(panel, matchWrap());

        LinearLayout hero = column(0);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setBackground(Ui.gradient(Ui.GREEN_BRIGHT, Ui.GREEN_DEEP));
        hero.setPadding(dp(24), dp(54), dp(24), dp(86));
        TextView seal = Ui.text(this, "林", 26, Color.WHITE, true);
        seal.setGravity(Gravity.CENTER);
        seal.setBackground(Ui.circle(Ui.GLASS_STRONG));
        hero.addView(seal, new LinearLayout.LayoutParams(dp(64), dp(64)));
        hero.addView(Ui.text(this, "中南林课表", 26, Color.WHITE, true), spaced(0, 16, 0, 0));
        hero.addView(Ui.text(this, "中南林业科技大学 · 一周课程，一眼看清", 13, Ui.WHITE_SOFT, false),
                spaced(0, 7, 0, 0));
        panel.addView(hero, matchWrap());

        LinearLayout card = column(0);
        card.setPadding(dp(22), dp(24), dp(22), dp(18));
        card.setBackground(Ui.rounded(this, Color.WHITE, 20, Ui.CARD_LINE));
        card.setElevation(dp(10));
        LinearLayout.LayoutParams cardParams = spacedMatch(20, -58, 20, 0, ViewGroup.LayoutParams.WRAP_CONTENT);
        panel.addView(card, cardParams);

        card.addView(Ui.text(this, "统一身份认证登录", 17, Ui.INK, true), spaced(0, 0, 0, 16));
        final EditText username = field("学号 / 工号 / 证件号码", false);
        final EditText password = field("密码", true);
        card.addView(username, matchHeight(50));
        card.addView(password, spacedMatch(0, 12, 0, 0, 50));

        final TextView error = Ui.text(this, "", 13, Ui.DANGER, false);
        error.setGravity(Gravity.CENTER);
        card.addView(error, spaced(0, 10, 0, 0));

        final Button login = button("登录并查询课表");
        card.addView(login, spacedMatch(0, 18, 0, 0, 50));
        Button smsLogin = smallButton("使用短信验证码登录");
        card.addView(smsLogin, spacedMatch(0, 10, 0, 2, ViewGroup.LayoutParams.WRAP_CONTENT));
        smsLogin.setOnClickListener(view -> new AuthDialogs(this, api, executor, this::handleLoginResult).sms());

        final ProgressBar loginProgress = new ProgressBar(this);
        loginProgress.setVisibility(View.GONE);
        panel.addView(loginProgress, centered(18));
        TextView note = Ui.text(this, "账号密码只发送到学校统一认证服务；应用不保存密码，仅在本机私有存储中保存登录 Token。",
                12, Ui.INK_FAINT, false);
        note.setGravity(Gravity.CENTER);
        panel.addView(note, spacedMatch(30, 20, 30, 28, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        login.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                final String name = username.getText().toString().trim();
                final String secret = password.getText().toString();
                if (name.isEmpty() || secret.isEmpty()) {
                    error.setText("请输入账号和密码");
                    return;
                }
                login.setEnabled(false);
                loginProgress.setVisibility(View.VISIBLE);
                error.setText("");
                executor.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            final ApiClient.LoginResult result = api.login(name, secret);
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    login.setEnabled(true);
                                    loginProgress.setVisibility(View.GONE);
                                    if (result.accounts != null) showAccountPicker(result);
                                    else acceptToken(result.token);
                                }
                            });
                        } catch (final Exception exception) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    login.setEnabled(true);
                                    loginProgress.setVisibility(View.GONE);
                                    error.setText(message(exception));
                                    if (exception instanceof ApiClient.MfaRequired) {
                                        password.setText("");
                                        new AuthDialogs(MainActivity.this, api, executor, MainActivity.this::handleLoginResult)
                                                .mfa((ApiClient.MfaRequired) exception);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    private void showAccountPicker(final ApiClient.LoginResult result) {
        final JSONArray accounts = result.accounts;
        String[] labels = new String[accounts.length()];
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject item = accounts.optJSONObject(i);
            if (item == null) labels[i] = "账号 " + (i + 1);
            else {
                String name = first(item, "name", "userName", "accountName", "identityName");
                String account = first(item, "accountName", "username", "code");
                labels[i] = name.isEmpty() ? account : name + (account.isEmpty() ? "" : " · " + account);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择登录身份")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, final int which) {
                        final JSONObject account = accounts.optJSONObject(which);
                        if (account == null) return;
                        final String accountId = first(account, "id", "accountId");
                        executor.execute(new Runnable() {
                            @Override public void run() {
                                try {
                                    final ApiClient.LoginResult chosen = api.chooseAccount(accountId, result.cid);
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() { acceptToken(chosen.token); }
                                    });
                                } catch (final Exception error) {
                                    runOnUiThread(new Runnable() {
                                        @Override public void run() { toast(message(error)); }
                                    });
                                }
                            }
                        });
                    }
                }).show();
    }

    private void handleLoginResult(ApiClient.LoginResult result) {
        if (result.accounts != null) showAccountPicker(result);
        else acceptToken(result.token);
    }

    private void acceptToken(String token) {
        api.setToken(token);
        preferences.edit().putString(KEY_TOKEN, token).apply();
        showSchedule(true);
    }

    private void showSchedule(boolean loadImmediately) {
        root = column(0);
        root.setBackgroundColor(Ui.BG);
        FrameLayout modules = new FrameLayout(this);
        calendarPanel = buildCalendarPanel();
        weekPanel = buildWeekPanel();
        modules.addView(calendarPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        modules.addView(weekPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(modules, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(buildBottomNavigation(), matchWrap());
        setContentView(root);
        updateWeekLabel();
        showModule(true);
        loadWeather();
        if (loadImmediately) loadCourses();
    }

    private LinearLayout header() {
        LinearLayout header = column(0);
        header.setBackground(Ui.gradient(Ui.GREEN_BRIGHT, Ui.GREEN_DEEP));
        return header;
    }

    private View buildWeekPanel() {
        LinearLayout panel = column(0);
        panel.setBackgroundColor(Ui.BG);

        LinearLayout header = header();
        header.setPadding(dp(18), dp(34), dp(14), dp(40));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(Ui.text(this, "课程表", 22, Color.WHITE, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        weekRefresh = pillButton("刷新", Color.WHITE, Ui.GLASS);
        Button settings = pillButton("设置", Color.WHITE, Ui.GLASS);
        toolbar.addView(weekRefresh, spaced(0, 0, 8, 0));
        toolbar.addView(settings);
        header.addView(toolbar, matchWrap());
        weekWeather = Ui.text(this, "长沙校区附近（固定地点）  天气加载中…", 12, Ui.WHITE_SOFT, false);
        weekWeather.setOnClickListener(view -> loadWeather());
        header.addView(weekWeather, spaced(1, 5, 0, 0));
        panel.addView(header, matchWrap());

        LinearLayout weekPicker = new LinearLayout(this);
        weekPicker.setGravity(Gravity.CENTER);
        weekPicker.setPadding(dp(4), dp(2), dp(4), dp(2));
        weekPicker.setBackground(Ui.rounded(this, Color.WHITE, 16, Ui.CARD_LINE));
        weekPicker.setElevation(dp(6));
        Button previous = arrowButton("‹");
        Button next = arrowButton("›");
        weekRangeLabel = Ui.text(this, "", 15, Ui.INK, true);
        weekRangeLabel.setGravity(Gravity.CENTER);
        weekPicker.addView(previous, wrapWrap());
        weekPicker.addView(weekRangeLabel, new LinearLayout.LayoutParams(0, dp(40), 1));
        weekPicker.addView(next, wrapWrap());
        panel.addView(weekPicker, spacedMatch(16, -22, 16, 0, ViewGroup.LayoutParams.WRAP_CONTENT));

        weekStatus = Ui.text(this, "", 12, Ui.INK_FAINT, false);
        weekStatus.setGravity(Gravity.CENTER);
        panel.addView(weekStatus, spacedMatch(12, 8, 12, 4, ViewGroup.LayoutParams.WRAP_CONTENT));
        weekProgress = new ProgressBar(this);
        weekProgress.setVisibility(View.GONE);
        panel.addView(weekProgress, centered(0));

        ScrollView scheduleScroll = new ScrollView(this);
        scheduleScroll.setClipToPadding(false);
        scheduleScroll.setPadding(dp(12), dp(2), dp(12), dp(16));
        weekScheduleView = new WeekScheduleView(this);
        scheduleScroll.addView(weekScheduleView, matchWrap());
        panel.addView(scheduleScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        previous.setOnClickListener(view -> { selectedDate.add(Calendar.DAY_OF_MONTH, -7); updateWeekLabel(); loadCourses(); });
        next.setOnClickListener(view -> { selectedDate.add(Calendar.DAY_OF_MONTH, 7); updateWeekLabel(); loadCourses(); });
        weekRangeLabel.setOnClickListener(view -> { selectedDate = Calendar.getInstance(); updateWeekLabel(); loadCourses(); });
        weekRefresh.setOnClickListener(view -> { loadCourses(); loadWeather(); });
        settings.setOnClickListener(view -> showSettings());
        return panel;
    }

    private View buildCalendarPanel() {
        LinearLayout panel = column(0);
        panel.setBackgroundColor(Ui.BG);

        LinearLayout header = header();
        header.setPadding(dp(20), dp(36), dp(14), dp(34));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = column(0);
        titles.addView(Ui.text(this, "日历", 24, Color.WHITE, true), matchWrap());
        calendarWeather = Ui.text(this, "长沙校区附近（固定地点）  天气加载中…", 12, Ui.WHITE_SOFT, false);
        calendarWeather.setOnClickListener(view -> loadWeather());
        titles.addView(calendarWeather, spacedMatch(0, 4, 0, 0, ViewGroup.LayoutParams.WRAP_CONTENT));
        toolbar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        calendarRefresh = pillButton("刷新", Color.WHITE, Ui.GLASS);
        Button settings = pillButton("设置", Color.WHITE, Ui.GLASS);
        toolbar.addView(calendarRefresh, spaced(0, 0, 8, 0));
        toolbar.addView(settings);
        header.addView(toolbar, matchWrap());
        panel.addView(header, matchWrap());

        calendarStatus = Ui.text(this, "只显示今天的课程", 12, Ui.INK_FAINT, false);
        calendarStatus.setGravity(Gravity.CENTER);
        panel.addView(calendarStatus, spacedMatch(16, 10, 16, 0, ViewGroup.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        calendarContent = column(0);
        calendarContent.setPadding(dp(18), dp(8), dp(18), dp(30));
        scroll.addView(calendarContent, matchWrap());
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        renderCalendar(null);
        calendarRefresh.setOnClickListener(view -> { loadCourses(); loadWeather(); });
        settings.setOnClickListener(view -> showSettings());
        return panel;
    }

    private View buildBottomNavigation() {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(24), dp(7), dp(24), dp(9));
        navigation.setBackgroundColor(Color.WHITE);
        navigation.setElevation(dp(14));
        calendarNav = navigationItem("▦", "日历");
        weekNav = navigationItem("▤", "课程表");
        navigation.addView(calendarNav, new LinearLayout.LayoutParams(0, dp(58), 1));
        navigation.addView(weekNav, new LinearLayout.LayoutParams(0, dp(58), 1));
        calendarNav.setOnClickListener(view -> showModule(false));
        weekNav.setOnClickListener(view -> showModule(true));
        return navigation;
    }

    private TextView navigationItem(String icon, String title) {
        SpannableStringBuilder label = new SpannableStringBuilder(icon);
        label.setSpan(new RelativeSizeSpan(1.35f), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.append('\n').append(title);
        TextView item = Ui.text(this, label, 12, Ui.INK_SOFT, true);
        item.setGravity(Gravity.CENTER);
        item.setLineSpacing(0, 1.1f);
        return item;
    }

    private void showModule(boolean week) {
        showingWeek = week;
        weekPanel.setVisibility(week ? View.VISIBLE : View.GONE);
        calendarPanel.setVisibility(week ? View.GONE : View.VISIBLE);
        styleNavigation(weekNav, week);
        styleNavigation(calendarNav, !week);
        if (!week) {
            Calendar now = Calendar.getInstance();
            if (!sameWeek(selectedDate, now)) {
                selectedDate = now;
                updateWeekLabel();
                loadCourses();
            } else {
                renderCalendar(currentBundle);
            }
        }
    }

    private void styleNavigation(TextView view, boolean selected) {
        view.setTextColor(selected ? Ui.GREEN : Ui.INK_SOFT);
        view.setBackground(Ui.rounded(this, selected ? Ui.GREEN_TINT : Color.TRANSPARENT,
                18, Color.TRANSPARENT));
    }

    private void loadWeather() {
        final int version = ++weatherVersion;
        updateWeatherText("长沙校区附近（固定地点）  天气加载中…");
        weatherExecutor.execute(() -> {
            String result;
            try {
                result = WeatherClient.fetch() + "  ·  Open-Meteo";
            } catch (Exception error) {
                result = "长沙校区附近（固定地点）  天气暂不可用，点击重试";
            }
            final String display = result;
            runOnUiThread(() -> {
                if (version != weatherVersion || isFinishing() || isDestroyed()) return;
                updateWeatherText(display);
            });
        });
    }

    private void updateWeatherText(String value) {
        if (weekWeather != null) weekWeather.setText(value);
        if (calendarWeather != null) calendarWeather.setText(value);
    }

    private void loadCourses() {
        final int version = ++requestVersion;
        weekRefresh.setEnabled(false);
        calendarRefresh.setEnabled(false);
        weekProgress.setVisibility(View.VISIBLE);
        weekStatus.setText("正在加载本周课程…");
        calendarStatus.setText("正在读取今天的课程…");
        Calendar start = weekStart(selectedDate);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_MONTH, 6);
        final SimpleDateFormat apiFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        final String startDate = apiFormat.format(start.getTime());
        final String endDate = apiFormat.format(end.getTime());
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final ApiClient.CourseBundle bundle = api.loadWeek(startDate, endDate);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (version != requestVersion || isDestroyed()) return;
                            weekRefresh.setEnabled(true);
                            calendarRefresh.setEnabled(true);
                            currentBundle = bundle;
                            renderWeek(bundle);
                            renderCalendar(bundle);
                        }
                    });
                } catch (final ApiClient.TokenException expired) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (version != requestVersion || isDestroyed()) return;
                            preferences.edit().remove(KEY_TOKEN).apply();
                            toast(expired.getMessage()); showLogin();
                        }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (version != requestVersion || isDestroyed()) return;
                            weekRefresh.setEnabled(true);
                            calendarRefresh.setEnabled(true);
                            weekProgress.setVisibility(View.GONE);
                            weekStatus.setText(message(error));
                            calendarStatus.setText(message(error));
                        }
                    });
                }
            }
        });
    }

    private void renderWeek(ApiClient.CourseBundle bundle) {
        weekProgress.setVisibility(View.GONE);
        weekStatus.setText((bundle.courses.isEmpty() ? "接口本周未返回课程" : "本周共 " + bundle.courses.size() + " 节课")
                + "  ·  " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new java.util.Date()) + " 更新");
        weekScheduleView.setBundle(bundle);
    }

    private void renderCalendar(ApiClient.CourseBundle bundle) {
        if (calendarContent == null) return;
        calendarContent.removeAllViews();
        Calendar today = Calendar.getInstance();
        String dateText = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(today.getTime());
        TextView heading = Ui.text(this, dateText, 22, Ui.INK, true);
        heading.setGravity(Gravity.CENTER);
        calendarContent.addView(heading, spacedMatch(0, 16, 0, 0, ViewGroup.LayoutParams.WRAP_CONTENT));

        ApiClient.CourseDay todayCourses = findDay(bundle, new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(today.getTime()));
        int count = todayCourses == null ? 0 : todayCourses.courses.size();
        TextView summary = Ui.text(this, count == 0 ? "当日无课" : "今日 " + count + " 节课程", 13,
                count == 0 ? Ui.INK_SOFT : Ui.GREEN, true);
        summary.setGravity(Gravity.CENTER);
        summary.setPadding(dp(16), dp(6), dp(16), dp(6));
        summary.setBackground(Ui.rounded(this, count == 0 ? Color.rgb(236, 240, 239) : Ui.GREEN_TINT, 16, Color.TRANSPARENT));
        LinearLayout.LayoutParams summaryParams = wrapWrap();
        summaryParams.gravity = Gravity.CENTER_HORIZONTAL;
        summaryParams.topMargin = dp(9);
        summaryParams.bottomMargin = dp(18);
        calendarContent.addView(summary, summaryParams);
        calendarStatus.setText(bundle == null ? "正在读取今天的课程…" : "只显示今天的课程 · "
                + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new java.util.Date()) + " 更新");

        if (bundle == null) return;
        if (count == 0) {
            LinearLayout empty = column(0);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(48), dp(24), dp(48));
            empty.setBackground(Ui.rounded(this, Color.WHITE, 24, Ui.CARD_LINE));
            empty.setElevation(dp(3));
            TextView seal = Ui.text(this, "休", 24, Ui.GREEN, true);
            seal.setGravity(Gravity.CENTER);
            seal.setBackground(Ui.circle(Ui.GREEN_TINT));
            empty.addView(seal, new LinearLayout.LayoutParams(dp(72), dp(72)));
            empty.addView(Ui.text(this, "今天没有课程安排", 17, Ui.INK, true), spaced(0, 20, 0, 0));
            empty.addView(Ui.text(this, "好好休息，或切换到课程表查看整周安排", 13, Ui.INK_FAINT, false),
                    spaced(0, 8, 0, 0));
            calendarContent.addView(empty, matchWrap());
            return;
        }
        for (ApiClient.Course course : todayCourses.courses) {
            calendarContent.addView(dailyCourseCard(course),
                    spacedMatch(0, 0, 0, 12, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private View dailyCourseCard(ApiClient.Course course) {
        LinearLayout card = column(0);
        card.setPadding(dp(22), dp(16), dp(18), dp(16));
        card.setBackground(Ui.whiteAccentCard(this, course.name));
        card.setElevation(dp(3));
        TextView time = Ui.text(this, prettyTime(course.time), 12, Ui.courseAccent(course.name), true);
        time.setGravity(Gravity.CENTER);
        time.setPadding(dp(11), dp(4), dp(11), dp(4));
        time.setBackground(Ui.rounded(this, Ui.courseTint(course.name), 12, Color.TRANSPARENT));
        card.addView(time, wrapWrap());
        card.addView(Ui.text(this, course.name, 19, Ui.INK, true),
                spacedMatch(0, 9, 0, 0, ViewGroup.LayoutParams.WRAP_CONTENT));
        String detail = (course.room.isEmpty() ? "" : "教室  " + course.room)
                + (course.teacher.isEmpty() ? "" : (course.room.isEmpty() ? "" : "    ") + "教师  " + course.teacher);
        if (!detail.isEmpty()) card.addView(Ui.text(this, detail, 13, Ui.INK_SOFT, false),
                spacedMatch(0, 8, 0, 0, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private ApiClient.CourseDay findDay(ApiClient.CourseBundle bundle, String date) {
        if (bundle == null) return null;
        for (ApiClient.CourseDay day : bundle.days) if (date.equals(day.date)) return day;
        return null;
    }

    private String semesterLabel(Calendar date) {
        int year = date.get(Calendar.YEAR);
        int month = date.get(Calendar.MONTH) + 1;
        if (month >= 8) return year + "-" + (year + 1) + "-1";
        if (month <= 1) return (year - 1) + "-" + year + "-1";
        return (year - 1) + "-" + year + "-2";
    }

    private boolean sameWeek(Calendar left, Calendar right) {
        return sameDay(weekStart(left), weekStart(right));
    }

    private void showSettings() {
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setMessage("课表来源：i中南林门户日程\nhttps://portal.csuft.edu.cn/portal-api")
                .setPositiveButton("关闭", null)
                .setNegativeButton("退出登录", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        preferences.edit().remove(KEY_TOKEN).apply();
                        api.setToken("");
                        showLogin();
                    }
                }).show();
    }

    private void updateWeekLabel() {
        Calendar start = weekStart(selectedDate);
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_MONTH, 6);
        String startText = new SimpleDateFormat("M月d日", Locale.CHINA).format(start.getTime());
        String endText = new SimpleDateFormat("M月d日", Locale.CHINA).format(end.getTime());
        if (weekRangeLabel != null) weekRangeLabel.setText(startText + " — " + endText);
    }

    private Calendar weekStart(Calendar source) {
        Calendar value = (Calendar) source.clone();
        int offset = (value.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        value.add(Calendar.DAY_OF_MONTH, -offset);
        value.set(Calendar.HOUR_OF_DAY, 0);
        value.set(Calendar.MINUTE, 0);
        value.set(Calendar.SECOND, 0);
        value.set(Calendar.MILLISECOND, 0);
        return value;
    }

    private boolean sameDay(Calendar left, Calendar right) {
        return left.get(Calendar.YEAR) == right.get(Calendar.YEAR)
                && left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR);
    }

    private String prettyTime(String value) {
        if (value == null || value.isEmpty()) return "课程";
        return value.replaceAll("(\\d{2}:\\d{2}):\\d{2}", "$1").replace(" - ", "—");
    }

    private LinearLayout column(int spacing) {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        if (spacing > 0) value.setPadding(dp(spacing), 0, dp(spacing), 0);
        return value;
    }

    private EditText field(String hint, boolean password) {
        EditText value = new EditText(this);
        value.setHint(hint);
        value.setTextSize(15);
        value.setTextColor(Ui.INK);
        value.setHintTextColor(Ui.INK_FAINT);
        value.setSingleLine(true);
        value.setPadding(dp(16), 0, dp(16), 0);
        if (password) value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        value.setBackground(Ui.rounded(this, Color.rgb(250, 252, 251), 13, Color.rgb(214, 224, 220)));
        return value;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        button.setBackground(Ui.gradient(Ui.GREEN_BRIGHT, Ui.GREEN));
        ((android.graphics.drawable.GradientDrawable) button.getBackground()).setCornerRadius(dp(25));
        return button;
    }

    private Button smallButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        button.setTextColor(Ui.GREEN);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(13), dp(9), dp(13), dp(9));
        button.setBackground(Ui.rounded(this, Color.TRANSPARENT, 13, Color.TRANSPARENT));
        return button;
    }

    private Button pillButton(String value, int textColor, int backgroundColor) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(12);
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setMinHeight(dp(34));
        button.setMinimumHeight(dp(34));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(13), 0, dp(13), 0);
        button.setHeight(dp(34));
        button.setBackground(Ui.rounded(this, backgroundColor, 22, Color.TRANSPARENT));
        return button;
    }

    private Button arrowButton(String value) {
        Button button = pillButton(value, Ui.INK_SOFT, Color.TRANSPARENT);
        button.setTextSize(20);
        button.setMinWidth(dp(42));
        button.setMinimumWidth(dp(42));
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private int dp(int value) { return Ui.dp(this, value); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams wrapWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams matchHeight(int height) { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)); }
    private LinearLayout.LayoutParams centered(int topMargin) { LinearLayout.LayoutParams p = wrapWrap(); p.gravity = Gravity.CENTER; p.topMargin = dp(topMargin); return p; }
    private LinearLayout.LayoutParams spaced(int l, int t, int r, int b) { LinearLayout.LayoutParams p = wrapWrap(); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams spacedMatch(int l, int t, int r, int b, int h) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h < 0 ? h : dp(h)); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }

    private static String first(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String message(Exception error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }

    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
