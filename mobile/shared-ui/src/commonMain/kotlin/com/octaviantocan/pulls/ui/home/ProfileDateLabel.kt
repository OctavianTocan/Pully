package com.octaviantocan.pulls.ui.home

import kotlinx.datetime.LocalDate

/** Native locale labels preserve the existing device-locale profile calendar; calendar/count policy stays shared. */
enum class ProfileDateStyle { DayShort, MonthShort, MonthFull, MonthNarrow }
expect fun profileDateLabel(date: LocalDate, style: ProfileDateStyle): String
