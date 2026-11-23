"""
產生桌面小工具裡「很多日期格」的版面與對應的 id 清單：

  - app/src/main/res/layout/widget_weeks.xml   5×2 兩週班表（14 格）
  - app/src/main/res/layout/widget_month.xml   5×5 整月班表（42 格）
  - app/src/main/java/com/reverie/alarm/WidgetIds.kt   兩個版面的格子 id 陣列

格子手寫很容易漏改或寫錯編號，所以改版面請改這支腳本再重新產生：
    python tools/widgets/gen_widget_layouts.py

桌面小工具只能用 RemoteViews 支援的元件（LinearLayout、FrameLayout、TextView…），
用了一般 View 會「載入小工具時出現問題」。
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LAYOUT = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'layout')
KOTLIN = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'reverie', 'alarm', 'WidgetIds.kt')
A = 'xmlns:android="http://schemas.android.com/apk/res/android"'
WEEKDAYS = '日一二三四五六'


def heads(p, size):
    return ''.join(f'''
        <TextView
            android:id="@+id/{p}_h{i}"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="center"
            android:includeFontPadding="false"
            android:text="{WEEKDAYS[i]}"
            android:textColor="#94A3B8"
            android:textSize="{size}sp"
            android:textStyle="bold" />''' for i in range(7))


def cell(p, i, num_sp, label_sp):
    return f'''
        <LinearLayout
            android:id="@+id/{p}_c{i}"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_margin="1dp"
            android:layout_weight="1"
            android:gravity="center"
            android:orientation="vertical">

            <TextView
                android:id="@+id/{p}_d{i}"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:includeFontPadding="false"
                android:text="{i % 28 + 1}"
                android:textColor="#F1F5F9"
                android:textSize="{num_sp}sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/{p}_s{i}"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:ellipsize="end"
                android:includeFontPadding="false"
                android:maxLines="1"
                android:text="夜班"
                android:textColor="#D8B4FE"
                android:textSize="{label_sp}sp"
                android:textStyle="bold" />
        </LinearLayout>'''


def row(p, start, num_sp, label_sp, row_id=None):
    cells = ''.join(cell(p, i, num_sp, label_sp) for i in range(start, start + 7))
    rid = f'\n        android:id="@+id/{row_id}"' if row_id else ''
    return f'''
    <LinearLayout{rid}
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:baselineAligned="false"
        android:orientation="horizontal">{cells}
    </LinearLayout>
'''


def header(p, month_sp, sub1_sp, sub2_sp, alarm_sp, btn_dp, arrow_sp, title_hint):
    """標題列：月份（點一下回到本週／本月）、副標兩行、下一個鬧鐘、上一頁／下一頁"""
    return f'''
    <!-- 標題：月份（{title_hint}）、副標、下一個鬧鐘、上一頁／下一頁 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <LinearLayout
            android:id="@+id/{p}_title"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/{p}_month"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:includeFontPadding="false"
                android:maxLines="1"
                android:text="11月"
                android:textColor="#F8FAFC"
                android:textSize="{month_sp}sp"
                android:textStyle="bold" />

            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/{p}_sub1"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:includeFontPadding="false"
                    android:maxLines="1"
                    android:text="週一"
                    android:textColor="#E2E8F0"
                    android:textSize="{sub1_sp}sp"
                    android:textStyle="bold" />

                <TextView
                    android:id="@+id/{p}_sub2"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:includeFontPadding="false"
                    android:maxLines="1"
                    android:text="上班 6・休 3"
                    android:textColor="#94A3B8"
                    android:textSize="{sub2_sp}sp" />
            </LinearLayout>
        </LinearLayout>

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="10dp"
            android:gravity="end"
            android:orientation="vertical">

            <TextView
                android:id="@+id/{p}_alarm_time"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:fontFamily="monospace"
                android:includeFontPadding="false"
                android:maxLines="1"
                android:text="17:40"
                android:textColor="#F8FAFC"
                android:textSize="{alarm_sp}sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/{p}_alarm_label"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:maxLines="1"
                android:text="今天 · 起床"
                android:textColor="#94A3B8"
                android:textSize="9sp" />
        </LinearLayout>

        <TextView
            android:id="@+id/{p}_prev"
            android:layout_width="{btn_dp}dp"
            android:layout_height="{btn_dp}dp"
            android:background="@drawable/widget_round_btn"
            android:contentDescription="上一頁"
            android:gravity="center"
            android:includeFontPadding="false"
            android:text="‹"
            android:textColor="#F1F5F9"
            android:textSize="{arrow_sp}sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/{p}_next"
            android:layout_width="{btn_dp}dp"
            android:layout_height="{btn_dp}dp"
            android:layout_marginStart="6dp"
            android:background="@drawable/widget_round_btn"
            android:contentDescription="下一頁"
            android:gravity="center"
            android:includeFontPadding="false"
            android:text="›"
            android:textColor="#F1F5F9"
            android:textSize="{arrow_sp}sp"
            android:textStyle="bold" />
    </LinearLayout>

    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:layout_marginTop="5dp"
        android:layout_marginBottom="3dp"
        android:background="#26FFFFFF" />
'''


def root(p, comment, pad, body):
    t, e, b, s = pad
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  {comment}
  由 tools/widgets/gen_widget_layouts.py 產生，請改腳本後重新產生，不要手動修改。
-->
<LinearLayout {A}
    android:id="@+id/{p}_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg_glass"
    android:orientation="vertical"
    android:paddingStart="{s}dp"
    android:paddingTop="{t}dp"
    android:paddingEnd="{e}dp"
    android:paddingBottom="{b}dp">
{body}</LinearLayout>
'''


# ---------------------------------------------------------------------------
# 5×2 兩週班表：桌面只給 2 格高，所以不放底部資訊列，日期格要留給班別名稱
# ---------------------------------------------------------------------------
weeks = root(
    'wk', '5×2 桌面小工具：兩週班表（日期格 wk_c0～wk_c13，數字 wk_d、班別 wk_s）', (8, 10, 6, 12),
    header('wk', 21, 11, 9, 14, 26, 17, '點一下回到本週')
    + f'''
    <!-- 星期 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginBottom="1dp"
        android:orientation="horizontal">{heads('wk', 10)}
    </LinearLayout>
''' + row('wk', 0, 15, 9) + row('wk', 7, 15, 9)
)

# ---------------------------------------------------------------------------
# 5×5 整月班表：6 週的格子（只需要 5 週的月份會把第 6 列藏起來），底下是今天／明天與本月統計
# ---------------------------------------------------------------------------
footer = '''
    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:layout_marginTop="5dp"
        android:layout_marginBottom="6dp"
        android:background="#26FFFFFF" />

    <!-- 今天、明天 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/mo_today_dot"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginEnd="6dp"
            android:includeFontPadding="false"
            android:text="●"
            android:textColor="#A855F7"
            android:textSize="11sp" />

        <TextView
            android:id="@+id/mo_today"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:ellipsize="end"
            android:includeFontPadding="false"
            android:maxLines="1"
            android:text="今天 夜班 · 19:00 - 07:00"
            android:textColor="#F1F5F9"
            android:textSize="13sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/mo_tomorrow"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:ellipsize="end"
            android:includeFontPadding="false"
            android:maxLines="1"
            android:text="明天 休假"
            android:textColor="#94A3B8"
            android:textSize="11sp" />
    </LinearLayout>

    <!-- 這個月的統計 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="5dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/mo_stats"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:ellipsize="end"
            android:includeFontPadding="false"
            android:maxLines="1"
            android:text="早 0・晚 0・夜 22・休 8"
            android:textColor="#CBD5E1"
            android:textSize="11sp" />

        <TextView
            android:id="@+id/mo_holiday"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:includeFontPadding="false"
            android:maxLines="1"
            android:text="國定假日上班 1 天"
            android:textColor="#FDA4AF"
            android:textSize="10sp" />
    </LinearLayout>
'''

month = root(
    'mo', '5×5 桌面小工具：整月班表（日期格 mo_c0～mo_c41，數字 mo_d、班別 mo_s；第 5、6 列 mo_row4、mo_row5，用不到時藏起來）', (12, 12, 10, 14),
    header('mo', 26, 12, 10, 16, 30, 19, '點一下回到本月')
    + f'''
    <!-- 星期 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginBottom="2dp"
        android:orientation="horizontal">{heads('mo', 11)}
    </LinearLayout>
''' + ''.join(row('mo', r * 7, 16, 9, f'mo_row{r}' if r >= 4 else None) for r in range(6)) + footer
)

open(os.path.join(LAYOUT, 'widget_weeks.xml'), 'w', encoding='utf-8').write(weeks)
open(os.path.join(LAYOUT, 'widget_month.xml'), 'w', encoding='utf-8').write(month)


def arr(name, p, kind, n):
    return f'    val {name} = intArrayOf(' + ', '.join(f'R.id.{p}_{kind}{i}' for i in range(n)) + ')'


kt = f'''package com.reverie.alarm

// ============================================================================
// 桌面小工具日期格的 id（由 tools/widgets/gen_widget_layouts.py 產生，請不要手動修改）
// ============================================================================

/** 一組日期格：格子底（cell）、日期數字（day）、班別名稱（label）、星期表頭（head） */
class WidgetGrid(val cell: IntArray, val day: IntArray, val label: IntArray, val head: IntArray)

object WidgetIds {{
    /** 5×2 兩週班表：14 格 */
    val WEEKS = WidgetGrid(
    {arr('c', 'wk', 'c', 14).strip().replace('val c = ', '')},
    {arr('d', 'wk', 'd', 14).strip().replace('val d = ', '')},
    {arr('s', 'wk', 's', 14).strip().replace('val s = ', '')},
    {arr('h', 'wk', 'h', 7).strip().replace('val h = ', '')}
    )

    /** 5×5 整月班表：42 格（6 週） */
    val MONTH = WidgetGrid(
    {arr('c', 'mo', 'c', 42).strip().replace('val c = ', '')},
    {arr('d', 'mo', 'd', 42).strip().replace('val d = ', '')},
    {arr('s', 'mo', 's', 42).strip().replace('val s = ', '')},
    {arr('h', 'mo', 'h', 7).strip().replace('val h = ', '')}
    )
}}
'''
open(KOTLIN, 'w', encoding='utf-8').write(kt)
print('written: widget_weeks.xml, widget_month.xml, WidgetIds.kt')
