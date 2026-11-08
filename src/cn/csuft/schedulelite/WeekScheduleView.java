package cn.csuft.schedulelite;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.widget.GridLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** A concrete grid so headers and section lines remain visible even on empty weeks. */
final class WeekScheduleView extends GridLayout {
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[] STARTS = {"08:00", "08:55", "10:00", "10:55", "14:00", "14:55", "16:00", "16:55", "19:00", "19:55", "20:50", "21:45"};
    private static final String[] ENDS = {"08:45", "09:40", "10:45", "11:40", "14:45", "15:40", "16:45", "17:40", "19:45", "20:40", "21:35", "22:30"};

    private ApiClient.CourseBundle bundle;
    private final int leftWidth;
    private final int headerHeight;
    private final int rowHeight;
    private final int breakHeight;

    WeekScheduleView(Context context) {
        super(context);
        leftWidth = dp(52);
        headerHeight = dp(56);
        rowHeight = dp(60);
        breakHeight = dp(24);
        setColumnCount(8);
        setRowCount(15);
        setUseDefaultMargins(false);
        setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        setBackground(Ui.rounded(context, Color.WHITE, 16, Ui.CARD_LINE));
        setPadding(dp(4), dp(4), dp(4), dp(6));
        setBundle(null);
    }

    void setBundle(ApiClient.CourseBundle value) {
        bundle = value;
        removeAllViews();
        buildHeaders();
        buildRows();
        buildBreak(5, "午休");
        buildBreak(10, "晚间");
        if (bundle != null) buildCourses();
        setContentDescription(bundle == null ? "课程表正在加载" : "本周共" + bundle.courses.size() + "节课程");
        requestLayout();
    }

    private void buildHeaders() {
        Calendar first = firstDay();
        addCell(first == null ? "" : (first.get(Calendar.MONTH) + 1) + "月",
                0, 0, leftWidth, headerHeight, Color.WHITE, true, 11, Color.TRANSPARENT);
        Calendar today = Calendar.getInstance();
        SimpleDateFormat date = new SimpleDateFormat("M/d", Locale.CHINA);
        for (int day = 0; day < 7; day++) {
            Calendar current = dayAt(day);
            boolean isToday = current != null && sameDay(current, today);
            CharSequence value = headerLabel(WEEKDAYS[day],
                    current == null ? "" : date.format(current.getTime()), isToday);
            addCell(value, 0, day + 1, 0, headerHeight,
                    isToday ? Ui.GREEN_TINT : Color.WHITE,
                    isToday, 12, Ui.GRID_LINE);
        }
    }

    private CharSequence headerLabel(String weekday, String date, boolean isToday) {
        SpannableStringBuilder label = new SpannableStringBuilder(weekday);
        if (date.isEmpty()) return label;
        int start = label.length();
        label.append('\n').append(date);
        label.setSpan(new RelativeSizeSpan(0.78f), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new ForegroundColorSpan(isToday ? Ui.GREEN : Ui.INK_FAINT),
                start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    private void buildRows() {
        int todayIndex = todayIndex();
        for (int section = 0; section < 12; section++) {
            int row = rowForSection(section);
            addCell(sectionLabel(section), row, 0, leftWidth, rowHeight, Color.WHITE,
                    false, 12, Ui.GRID_LINE);
            for (int day = 0; day < 7; day++) {
                addCell("", row, day + 1, 0, rowHeight,
                        day == todayIndex ? Color.rgb(241, 250, 246) : Color.WHITE,
                        false, 11, Ui.GRID_LINE);
            }
        }
    }

    private CharSequence sectionLabel(int section) {
        SpannableStringBuilder label = new SpannableStringBuilder(String.valueOf(section + 1));
        label.setSpan(new StyleSpan(Typeface.BOLD), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = label.length();
        label.append('\n').append(STARTS[section]).append('\n').append(ENDS[section]);
        label.setSpan(new ForegroundColorSpan(Ui.INK_FAINT), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new RelativeSizeSpan(0.72f), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    private void buildBreak(int row, String title) {
        TextView label = text(title, 11, Ui.INK_FAINT, false);
        label.setGravity(Gravity.CENTER);
        label.setBackgroundColor(Color.rgb(244, 247, 249));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(row, 1), GridLayout.spec(0, 8));
        params.width = 0;
        params.height = breakHeight;
        params.setGravity(Gravity.FILL);
        addView(label, params);
    }

    private void buildCourses() {
        for (int dayIndex = 0; dayIndex < bundle.days.size() && dayIndex < 7; dayIndex++) {
            for (ApiClient.Course course : bundle.days.get(dayIndex).courses) {
                int start = sectionFor(firstTime(course.time), STARTS);
                int end = sectionFor(lastTime(course.time), ENDS);
                if (start < 0) continue;
                if (end < start) end = start;
                int span = end - start + 1;
                TextView card = text(courseLabel(course), 11, Ui.INK, false);
                card.setGravity(Gravity.CENTER);
                card.setPadding(dp(9), dp(4), dp(4), dp(4));
                card.setBackground(Ui.courseCard(getContext(), course.name));
                card.setMaxLines(Math.max(3, span * 4));

                GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                        GridLayout.spec(rowForSection(start), span), GridLayout.spec(dayIndex + 1, 1, 1f));
                params.width = 0;
                params.height = rowHeight * span - dp(6);
                params.setMargins(dp(3), dp(3), dp(3), dp(3));
                params.setGravity(Gravity.FILL);
                addView(card, params);
            }
        }
    }

    private CharSequence courseLabel(ApiClient.Course course) {
        SpannableStringBuilder label = new SpannableStringBuilder(course.name);
        label.setSpan(new StyleSpan(Typeface.BOLD), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!course.room.isEmpty()) appendDetail(label, "@" + course.room);
        if (!course.teacher.isEmpty()) appendDetail(label, course.teacher);
        return label;
    }

    private void appendDetail(SpannableStringBuilder label, String detail) {
        int start = label.length();
        label.append('\n').append(detail);
        label.setSpan(new ForegroundColorSpan(Ui.INK_SOFT), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new RelativeSizeSpan(0.85f), start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void addCell(CharSequence value, int row, int column, int width, int height,
                         int background, boolean bold, int size, int stroke) {
        TextView cell = text(value, size, bold ? Ui.GREEN : Ui.INK, bold);
        cell.setGravity(Gravity.CENTER);
        cell.setBackground(Ui.rounded(getContext(), background, 0, stroke));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(row, 1), column == 0 ? GridLayout.spec(column, 1) : GridLayout.spec(column, 1, 1f));
        params.width = width;
        params.height = height;
        params.setGravity(Gravity.FILL);
        addView(cell, params);
    }

    private Calendar firstDay() {
        return dayAt(0);
    }

    private Calendar dayAt(int day) {
        if (bundle == null || day >= bundle.days.size()) return null;
        try {
            Calendar current = Calendar.getInstance();
            current.setTime(new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(bundle.days.get(day).date));
            return current;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int todayIndex() {
        if (bundle == null) return -1;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Calendar.getInstance().getTime());
        for (int i = 0; i < bundle.days.size(); i++) if (today.equals(bundle.days.get(i).date)) return i;
        return -1;
    }

    private int rowForSection(int section) {
        return 1 + section + (section >= 4 ? 1 : 0) + (section >= 8 ? 1 : 0);
    }

    private int sectionFor(String time, String[] values) {
        if (time.isEmpty()) return -1;
        for (int i = 0; i < values.length; i++) if (values[i].equals(time)) return i;
        int target = minutes(time), best = -1, distance = Integer.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            int current = Math.abs(minutes(values[i]) - target);
            if (current < distance) { distance = current; best = i; }
        }
        return best;
    }

    private String firstTime(String value) {
        if (value == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d{2}:\\d{2}").matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    private String lastTime(String value) {
        if (value == null) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d{2}:\\d{2}").matcher(value);
        String last = "";
        while (matcher.find()) last = matcher.group();
        return last;
    }

    private int minutes(String value) {
        try { return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3, 5)); }
        catch (Exception ignored) { return 0; }
    }

    private boolean sameDay(Calendar left, Calendar right) {
        return left.get(Calendar.YEAR) == right.get(Calendar.YEAR)
                && left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR);
    }

    private TextView text(CharSequence value, int size, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private int dp(float value) { return Ui.dp(getContext(), value); }
}
