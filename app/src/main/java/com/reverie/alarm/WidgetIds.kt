package com.reverie.alarm

// ============================================================================
// 桌面小工具日期格的 id（由 tools/widgets/gen_widget_layouts.py 產生，請不要手動修改）
// ============================================================================

/** 一組日期格：格子底（cell）、日期數字（day）、班別名稱（label）、星期表頭（head） */
class WidgetGrid(val cell: IntArray, val day: IntArray, val label: IntArray, val head: IntArray)

object WidgetIds {
    /** 5×2 兩週班表：14 格 */
    val WEEKS = WidgetGrid(
    intArrayOf(R.id.wk_c0, R.id.wk_c1, R.id.wk_c2, R.id.wk_c3, R.id.wk_c4, R.id.wk_c5, R.id.wk_c6, R.id.wk_c7, R.id.wk_c8, R.id.wk_c9, R.id.wk_c10, R.id.wk_c11, R.id.wk_c12, R.id.wk_c13),
    intArrayOf(R.id.wk_d0, R.id.wk_d1, R.id.wk_d2, R.id.wk_d3, R.id.wk_d4, R.id.wk_d5, R.id.wk_d6, R.id.wk_d7, R.id.wk_d8, R.id.wk_d9, R.id.wk_d10, R.id.wk_d11, R.id.wk_d12, R.id.wk_d13),
    intArrayOf(R.id.wk_s0, R.id.wk_s1, R.id.wk_s2, R.id.wk_s3, R.id.wk_s4, R.id.wk_s5, R.id.wk_s6, R.id.wk_s7, R.id.wk_s8, R.id.wk_s9, R.id.wk_s10, R.id.wk_s11, R.id.wk_s12, R.id.wk_s13),
    intArrayOf(R.id.wk_h0, R.id.wk_h1, R.id.wk_h2, R.id.wk_h3, R.id.wk_h4, R.id.wk_h5, R.id.wk_h6)
    )

    /** 5×5 整月班表：42 格（6 週） */
    val MONTH = WidgetGrid(
    intArrayOf(R.id.mo_c0, R.id.mo_c1, R.id.mo_c2, R.id.mo_c3, R.id.mo_c4, R.id.mo_c5, R.id.mo_c6, R.id.mo_c7, R.id.mo_c8, R.id.mo_c9, R.id.mo_c10, R.id.mo_c11, R.id.mo_c12, R.id.mo_c13, R.id.mo_c14, R.id.mo_c15, R.id.mo_c16, R.id.mo_c17, R.id.mo_c18, R.id.mo_c19, R.id.mo_c20, R.id.mo_c21, R.id.mo_c22, R.id.mo_c23, R.id.mo_c24, R.id.mo_c25, R.id.mo_c26, R.id.mo_c27, R.id.mo_c28, R.id.mo_c29, R.id.mo_c30, R.id.mo_c31, R.id.mo_c32, R.id.mo_c33, R.id.mo_c34, R.id.mo_c35, R.id.mo_c36, R.id.mo_c37, R.id.mo_c38, R.id.mo_c39, R.id.mo_c40, R.id.mo_c41),
    intArrayOf(R.id.mo_d0, R.id.mo_d1, R.id.mo_d2, R.id.mo_d3, R.id.mo_d4, R.id.mo_d5, R.id.mo_d6, R.id.mo_d7, R.id.mo_d8, R.id.mo_d9, R.id.mo_d10, R.id.mo_d11, R.id.mo_d12, R.id.mo_d13, R.id.mo_d14, R.id.mo_d15, R.id.mo_d16, R.id.mo_d17, R.id.mo_d18, R.id.mo_d19, R.id.mo_d20, R.id.mo_d21, R.id.mo_d22, R.id.mo_d23, R.id.mo_d24, R.id.mo_d25, R.id.mo_d26, R.id.mo_d27, R.id.mo_d28, R.id.mo_d29, R.id.mo_d30, R.id.mo_d31, R.id.mo_d32, R.id.mo_d33, R.id.mo_d34, R.id.mo_d35, R.id.mo_d36, R.id.mo_d37, R.id.mo_d38, R.id.mo_d39, R.id.mo_d40, R.id.mo_d41),
    intArrayOf(R.id.mo_s0, R.id.mo_s1, R.id.mo_s2, R.id.mo_s3, R.id.mo_s4, R.id.mo_s5, R.id.mo_s6, R.id.mo_s7, R.id.mo_s8, R.id.mo_s9, R.id.mo_s10, R.id.mo_s11, R.id.mo_s12, R.id.mo_s13, R.id.mo_s14, R.id.mo_s15, R.id.mo_s16, R.id.mo_s17, R.id.mo_s18, R.id.mo_s19, R.id.mo_s20, R.id.mo_s21, R.id.mo_s22, R.id.mo_s23, R.id.mo_s24, R.id.mo_s25, R.id.mo_s26, R.id.mo_s27, R.id.mo_s28, R.id.mo_s29, R.id.mo_s30, R.id.mo_s31, R.id.mo_s32, R.id.mo_s33, R.id.mo_s34, R.id.mo_s35, R.id.mo_s36, R.id.mo_s37, R.id.mo_s38, R.id.mo_s39, R.id.mo_s40, R.id.mo_s41),
    intArrayOf(R.id.mo_h0, R.id.mo_h1, R.id.mo_h2, R.id.mo_h3, R.id.mo_h4, R.id.mo_h5, R.id.mo_h6)
    )
}
