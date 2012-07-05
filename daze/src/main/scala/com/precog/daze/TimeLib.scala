/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog
package daze

import bytecode.Library

import yggdrasil._
import yggdrasil.table._

import org.joda.time._
import org.joda.time.format._

object TimeLib extends TimeLib

trait TimeLib extends GenOpcode with ImplLibrary {
  val TimeNamespace = Vector("std", "time")

  override def _lib1 = super._lib1 ++ Set(
    GetMillis,
    TimeZone,
    Season,

    Year,
    QuarterOfYear,
    MonthOfYear,
    WeekOfYear,
    WeekOfMonth,
    DayOfYear,
    DayOfMonth,
    DayOfWeek,
    HourOfDay,
    MinuteOfHour,
    SecondOfMinute,
    MillisOfSecond,

    Date,
    YearMonth,
    YearDayOfYear,
    MonthDay,
    DateHour,
    DateHourMinute,
    DateHourMinuteSecond,
    DateHourMinuteSecondMillis,
    TimeWithZone,
    TimeWithoutZone,
    HourMinute,
    HourMinuteSecond
  )

  override def _lib2 = super._lib2 ++ Set(
    YearsBetween,
    MonthsBetween,
    WeeksBetween,
    DaysBetween,
    HoursBetween,
    MinutesBetween,
    SecondsBetween,
    MillisBetween,

    MillisToISO,
    ChangeTimeZone
  )

  private def isValidISO(str: String): Boolean = {
    try { new DateTime(str); true
    } catch {
      case e:IllegalArgumentException => { false }
    }
  }

  private def isValidTimeZone(str: String): Boolean = {
    try { DateTimeZone.forID(str); true
    } catch {
      case e:IllegalArgumentException => { false }
    }
  }

  DateTimeZone.setDefault(DateTimeZone.UTC)

  object ParseDateTime extends Op2(TimeNamespace, "parse") {
    def f2: F2 = new CF2P({
      case (c1: StrColumn, c2: StrColumn) => new Map2Column(c1, c2) with DateColumn {
        def apply(row: Int) = {
          val time = c1(row)
          val fmt = c2(row)

          if (isValidFormat(time, fmt)) {
            val format = DateTimeFormat.forPattern(fmt).withOffsetParsed()
            val ISO = format.parseDateTime(time)

            ISO.toString()
          } else sys.error("todo")
        }
      }

    //val operandType = (Some(SString), Some(SString))
    //val operation: PartialFunction[(SValue, SValue), SValue] = {
    //  case (SString(time), SString(fmt)) if (isValidFormat(time, fmt)) =>
    //    val format = DateTimeFormat.forPattern(fmt).withOffsetParsed()
    //    val ISO = format.parseDateTime(time)
    //    SString(ISO.toString())
    })
  }

  object ChangeTimeZone extends Op2(TimeNamespace, "changeTimeZone") {
    def f2: F2 = new CF2P({
      case (c1: DateColumn, c2: StrColumn) => new Map2Column(c1, c2) with DateColumn {
        def apply(row: Int) = {
          val time = c1(row)
          val tz = c2(row)

          if (isValidISO(time) && isValidTimeZone(tz)) {
            val newTime = ISODateTimeFormat.dateTimeParser().parseDateTime(time)
            val timeZone = DateTimeZone.forID(tz)
            val dateTime = new DateTime(newTime, timeZone)

            dateTime.toString()
          } else sys.error("todo")
        }
      }
    })
    
    /* val operandType = (Some(SString), Some(SString))
    val operation: PartialFunction[(SValue, SValue), SValue] = {
      case (SString(time), SString(tz)) if (isValidISO(time) && isValidTimeZone(tz)) => 
        val format = ISODateTimeFormat.dateTime()
        val timeZone = DateTimeZone.forID(tz)
        val dateTime = new DateTime(time, timeZone)
        SString(format.print(dateTime))
    } */
  }

  trait TimePlus extends Op2 {
    def f2: F2 = new CF2P({
      case (c1: DateColumn, c2: LongColumn) => new Map2Column(c1, c2) with DateColumn {
        def apply(row: Int) = {
          val time = c1(row)
          val incr = c2(row)

          if (isValidISO(time) && isValidInt(incr)) {   //TODO test for isValidInt case
            val newTime = ISODateTimeFormat.dateTimeParser().withOffsetParsed.parseDateTime(time1)

            plus(newTime, incr.toInt)
          } else sys.error("todo'")
        }
      }
    })

    //val operandType = (Some(SString), Some(SDecimal)) 

    //val operation: PartialFunction[(SValue, SValue), SValue] = {
    //  case (SString(time), SDecimal(incr)) if isValidISO(time) => 
    //    val newTime = ISODateTimeFormat.dateTimeParser().withOffsetParsed.parseDateTime(time)
    //    SString(plus(newTime, incr.toInt))
    //}

    def plus(d: DateTime, i: Int): String
  }

  object YearsPlus extends Op2(TimeNamespace, "yearsPlus") with TimePlus{ 
    def plus(d: DateTime, i: Int) = d.plus(Period.years(i)).toString()
  }

  object MonthsPlus extends Op2(TimeNamespace, "monthsPlus") with TimePlus{ 
    def plus(d: DateTime, i: Int) = d.plus(Period.months(i)).toString()
  }

  object WeeksPlus extends Op2(TimeNamespace, "weeksPlus") with TimePlus{ 
    def plus(d: DateTime, i: Int) = d.plus(Period.weeks(i)).toString()
  }

  object DaysPlus extends Op2(TimeNamespace, "daysPlus") with TimePlus{ 
    def plus(d: DateTime, i: Int) = d.plus(Period.days(i)).toString()
  }

  object HoursPlus extends Op2(TimeNamespace, "hoursPlus") with TimePlus{ 
    def plus(d: DateTime, i: Int) = d.plus(Period.hours(i)).toString()
  }

  object MinutesPlus extends Op2(TimeNamespace, "minutesPlus") with TimePlus{ 
    def plus(d: DateTime, i: Int) = d.plus(Period.minutes(i)).toString()
  }

  object SecondsPlus extends Op2(TimeNamespace, "secondsPlus") with TimePlus{ 
    def plus(d: DateTime, i: Int) = d.plus(Period.seconds(i)).toString()
  }

  object MillisPlus extends Op2(TimeNamespace, "millisPlus") with TimePlus{
    def plus(d: DateTime, i: Int) = d.plus(Period.millis(i)).toString()
  }
  

  trait TimeBetween extends Op2 {
    def f2: F2 = new CF2P({
      case (c1: DateColumn, c2: DateColumn) => new Map2Column(c1, c2) with LongColumn {
        def apply(row: Int) = {
          val time1 = c1(row)
          val time2 = c2(row)

          if (isValidISO(time1) && isValidISO(time2)) {
            val newTime1 = ISODateTimeFormat.dateTimeParser().withOffsetParsed.parseDateTime(time1)
            val newTime2 = ISODateTimeFormat.dateTimeParser().withOffsetParsed.parseDateTime(time2)

            between(newTime1, newTime2)
          } else sys.error("todo'")
        }

        def between(d1: DateTime, d2: DateTime): Long
      }
    })
    /* val operandType = (Some(SString), Some(SString)) 

    val operation: PartialFunction[(SValue, SValue), SValue] = {
      case (SString(time1), SString(time2)) if (isValidISO(time1) && isValidISO(time2)) => 
        val newTime1 = ISODateTimeFormat.dateTime().withOffsetParsed.parseDateTime(time1)
        val newTime2 = ISODateTimeFormat.dateTime().withOffsetParsed.parseDateTime(time2)
        SDecimal(between(newTime1, newTime2))
    }

    def between(d1: DateTime, d2: DateTime): Long */
  }

  object YearsBetween extends Op2(TimeNamespace, "yearsBetween") with TimeBetween{
    def between(d1: DateTime, d2: DateTime) = Years.yearsBetween(d1, d2).getYears
  }

  object MonthsBetween extends Op2(TimeNamespace, "monthsBetween") with TimeBetween{
    def between(d1: DateTime, d2: DateTime) = Months.monthsBetween(d1, d2).getMonths
  }

  object WeeksBetween extends Op2(TimeNamespace, "weeksBetween") with TimeBetween{
    def between(d1: DateTime, d2: DateTime) = Weeks.weeksBetween(d1, d2).getWeeks
  }

  object DaysBetween extends Op2(TimeNamespace, "daysBetween") with TimeBetween{
    def between(d1: DateTime, d2: DateTime) = Days.daysBetween(d1, d2).getDays
  }

  object HoursBetween extends Op2(TimeNamespace, "hoursBetween") with TimeBetween{
    def between(d1: DateTime, d2: DateTime) = Hours.hoursBetween(d1, d2).getHours
  }

  object MinutesBetween extends Op2(TimeNamespace, "minutesBetween") with TimeBetween{
    def between(d1: DateTime, d2: DateTime) = Minutes.minutesBetween(d1, d2).getMinutes
  }

  object SecondsBetween extends Op2(TimeNamespace, "secondsBetween") with TimeBetween{
    def between(d1: DateTime, d2: DateTime) = Seconds.secondsBetween(d1, d2).getSeconds
  }

  object MillisBetween extends Op2(TimeNamespace, "millisBetween") with TimeBetween{
    def between(d1: DateTime, d2: DateTime) = d2.getMillis - d1.getMillis
  }

  object MillisToISO extends Op2(TimeNamespace, "millisToISO") {
    def f2: F2 = new CF2P({
      case (c1: LongColumn, c2: StrColumn) => new Map2Column(c1, c2) with DateColumn {
        def apply(row: Int) = { 
          val time = c1(row)
          val tz = c2(row)

          if (time >= Long.MinValue && time <= Long.MaxValue && isValidTimeZone(tz)){
            val timeZone = DateTimeZone.forID(tz)
            val dateTime = new DateTime(time.toLong, timeZone)

            dateTime.toString()
          } else sys.error("todo")
        }
      }
    })
    
    /* val operandType = (Some(SDecimal), Some(SString))
    val operation: PartialFunction[(SValue, SValue), SValue] = {
      case (SDecimal(time), SString(tz)) if (time >= Long.MinValue && time <= Long.MaxValue && isValidTimeZone(tz)) =>  
        val format = ISODateTimeFormat.dateTime()
        val timeZone = DateTimeZone.forID(tz)
        val dateTime = new DateTime(time.toLong, timeZone)
        SString(format.print(dateTime))
    } */
  }

  object GetMillis extends Op1(TimeNamespace, "getMillis") {
    def f1: F1 = new CF1P({
      case c: DateColumn => new Map1Column(c) with LongColumn {
        def apply(row: Int) = {
          val time = c(row)

          if (isValidISO(time)) {
            val newTime = ISODateTimeFormat.dateTimeParser().withOffsetParsed.parseDateTime(time)
            newTime.getMillis()
          } else sys.error("todo")
        }
      }
    })
    
    /* val operandType = Some(SString)
    val operation: PartialFunction[SValue, SValue] = {
      case SString(time) if isValidISO(time) => 
        val newTime = ISODateTimeFormat.dateTime().withOffsetParsed.parseDateTime(time)
        SDecimal(newTime.getMillis)
    } */    
  }

  object TimeZone extends Op1(TimeNamespace, "timeZone") {
    def f1: F1 = new CF1P({
      case c: DateColumn => new Map1Column(c) with StrColumn {
        def apply(row: Int) = { 
          val time = c1(row)

          if (isValidISO(time)) {
            val format = DateTimeFormat.forPattern("ZZ")
            val newTime = ISODateTimeFormat.dateTimeParser().withOffsetParsed.parseDateTime(time)
            format.print(newTime)
          } else sys.error("todo")
        }
      }
    })
    
    /* val operandType = Some(SString)
    val operation: PartialFunction[SValue, SValue] = {
      case SString(time) if isValidISO(time) => 
        val format = DateTimeFormat.forPattern("ZZ")
        val newTime = ISODateTimeFormat.dateTime().withOffsetParsed.parseDateTime(time)
        SString(format.print(newTime))
    } */
  }

  object Season extends Op1(TimeNamespace, "season") {
    def f1: F1 = new CF1P({
      case c: DateColumn => new Map1Column(c) with StrColumn {
        def apply(row: Int) = {
          val time = c(row)

          if (isValidISO(time)) {
            val newTime = ISODateTimeFormat.dateTimeParser().withOffsetParsed.parseDateTime(time)
            val day = newTime.dayOfYear.get
            
            if (day >= 79 & day < 171) "spring"
            else if (day >= 171 & day < 265) "summer"
            else if (day >= 265 & day < 355) "fall"
            else "winter"
          } else sys.error("todo")  //todo TEST THIS!!!!!!!!!!!!!!!!!!!!
        }
      }
    })
    
    /* val operandType = Some(SString)
    val operation: PartialFunction[SValue, SValue] = {
      case SString(time) if isValidISO(time) => 
        val newTime = ISODateTimeFormat.dateTime().withOffsetParsed.parseDateTime(time)
        val day = newTime.dayOfYear.get
        SString(
          if (day >= 79 & day < 171) "spring"
          else if (day >= 171 & day < 265) "summer"
          else if (day >= 265 & day < 355) "fall"
          else "winter"
        )
    } */
  } 

  trait TimeFraction extends Op1 {
    def f1: F1 = new CF1P({
      case c: DateColumn => new Map1Column(c) with LongColumn {
        def apply(row: Int) = {
          val time = c(row)

          if (isValidISO(time)) {
            val newTime = ISODateTimeFormat.dateTimeParser().withOffsetParsed.parseDateTime(time)
            fraction(newTime)
          } else {
            sys.error("todo")     // DEATH!
          }
        }

        def fraction(d: DateTime): Int
      }
    })
    /* val operandType = Some(SString)
    val operation: PartialFunction[SValue, SValue] = {
      case SString(time) if isValidISO(time) => 
        val newTime = ISODateTimeFormat.dateTime().withOffsetParsed.parseDateTime(time)
        SDecimal(fraction(newTime))
    }

    def fraction(d: DateTime): Int */
  }

  object Year extends Op1(TimeNamespace, "year") with TimeFraction {
    def fraction(d: DateTime) = d.year.get
  }

  object QuarterOfYear extends Op1(TimeNamespace, "quarter") with TimeFraction {
    def fraction(d: DateTime) = ((d.monthOfYear.get - 1) / 3) + 1
  }

  object MonthOfYear extends Op1(TimeNamespace, "monthOfYear") with TimeFraction {
    def fraction(d: DateTime) = d.monthOfYear.get
  }

  object WeekOfYear extends Op1(TimeNamespace, "weekOfYear") with TimeFraction {
    def fraction(d: DateTime) = d.weekOfWeekyear.get
  } 

  object WeekOfMonth extends Op1(TimeNamespace, "weekOfMonth") with TimeFraction {
    def fraction(newTime: DateTime) = {
      val dayOfMonth = newTime.dayOfMonth().get
      val firstDate = newTime.withDayOfMonth(1)
      val firstDayOfWeek = firstDate.dayOfWeek().get
      val offset = firstDayOfWeek - 1
      ((dayOfMonth + offset) / 7) + 1
    } 
  }
 
  object DayOfYear extends Op1(TimeNamespace, "dayOfYear") with TimeFraction {
    def fraction(d: DateTime) = d.dayOfYear.get
  }

  object DayOfMonth extends Op1(TimeNamespace, "dayOfMonth") with TimeFraction {
    def fraction(d: DateTime) = d.dayOfMonth.get
  }

  object DayOfWeek extends Op1(TimeNamespace, "dayOfWeek") with TimeFraction {
    def fraction(d: DateTime) = d.dayOfWeek.get
  }

  object HourOfDay extends Op1(TimeNamespace, "hourOfDay") with TimeFraction {
    def fraction(d: DateTime) = d.hourOfDay.get
  }

  object MinuteOfHour extends Op1(TimeNamespace, "minuteOfHour") with TimeFraction {
    def fraction(d: DateTime) = d.minuteOfHour.get
  }

  object SecondOfMinute extends Op1(TimeNamespace, "secondOfMinute") with TimeFraction {
    def fraction(d: DateTime) = d.secondOfMinute.get
  }
    
  object MillisOfSecond extends Op1(TimeNamespace, "millisOfSecond") with TimeFraction {
    def fraction(d: DateTime) = d.millisOfSecond.get
  }

  trait TimeTruncation extends Op1 {
    def fmt: DateTimeFormatter

    def f1: F1 = new CF1P({
      case c: DateColumn => new Map1Column(c) with DateColumn {
        def apply(row: Int) = {
          val time = c(row)

          if (isValidISO(time)) {
            val newTime = ISODateTimeFormat.dateTimeParser().withOffsetParsed.parseDateTime(time)
            fmt.print(newTime)
          } else sys.error("todo")
        }
      }
    })

    /* val operandType = Some(SString)
    val operation: PartialFunction[SValue, SValue] = {
      case SString(time) if isValidISO(time) => 
        val newTime = ISODateTimeFormat.dateTime().withOffsetParsed.parseDateTime(time)
        SString(fmt.print(newTime))
    }

    def fmt: DateTimeFormatter */
  }

  object Date extends Op1(TimeNamespace, "date") with TimeTruncation {
    val fmt = ISODateTimeFormat.date()
  }

  object YearMonth extends Op1(TimeNamespace, "yearMonth") with TimeTruncation {
    val fmt = ISODateTimeFormat.yearMonth()
  }

  object YearDayOfYear extends Op1(TimeNamespace, "yearDayOfYear") with TimeTruncation {
    val fmt = ISODateTimeFormat.ordinalDate()
  }

  object MonthDay extends Op1(TimeNamespace, "monthDay") with TimeTruncation {
    val fmt = DateTimeFormat.forPattern("MM-dd")
  }

  object DateHour extends Op1(TimeNamespace, "dateHour") with TimeTruncation {
    val fmt = ISODateTimeFormat.dateHour()
  }

  object DateHourMinute extends Op1(TimeNamespace, "dateHourMin") with TimeTruncation {
    val fmt = ISODateTimeFormat.dateHourMinute()
  }

  object DateHourMinuteSecond extends Op1(TimeNamespace, "dateHourMinSec") with TimeTruncation {
    val fmt = ISODateTimeFormat.dateHourMinuteSecond()
  }

  object DateHourMinuteSecondMillis extends Op1(TimeNamespace, "dateHourMinSecMilli") with TimeTruncation {
    val fmt = ISODateTimeFormat.dateHourMinuteSecondMillis()
  }

  object TimeWithZone extends Op1(TimeNamespace, "timeWithZone") with TimeTruncation {
    val fmt = ISODateTimeFormat.time()
  }

  object TimeWithoutZone extends Op1(TimeNamespace, "timeWithoutZone") with TimeTruncation {
    val fmt = ISODateTimeFormat.hourMinuteSecondMillis()
  }

  object HourMinute extends Op1(TimeNamespace, "hourMin") with TimeTruncation {
    val fmt = ISODateTimeFormat.hourMinute()
  }

  object HourMinuteSecond extends Op1(TimeNamespace, "hourMinSec") with TimeTruncation {
    val fmt = ISODateTimeFormat.hourMinuteSecond()
  }
}
