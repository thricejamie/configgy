/*
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.logging

import java.io.{File, FileOutputStream, OutputStreamWriter, Writer}
import java.text.SimpleDateFormat
import java.util.{Calendar, Date, logging => javalog}
import net.lag.HandleSignal

sealed abstract class Policy
case object Never extends Policy
case object Hourly extends Policy
case object Daily extends Policy
case class Weekly(dayOfWeek: Int) extends Policy
case class MaxSize(sizeBytes: Long) extends Policy

/**
 * A log handler that writes log entries into a file, and rolls this file
 * at a requested interval (hourly, daily, or weekly).
 */
class FileHandler(val filename: String, val policy: Policy, formatter: Formatter,
                  val append: Boolean, val handleSighup: Boolean) extends Handler(formatter) {
  private var stream: Writer = null
  private var openTime: Long = 0
  private var nextRollTime: Long = 0
  private var bytesWrittenToFile: Long = 0
  var rotateCount = -1
  openLog()

  if (handleSighup) {
    HandleSignal("HUP") { signal =>
      val oldStream = stream
      synchronized {
        stream = openWriter()
      }
      try {
        oldStream.close()
      } catch { case _ => () }
    }
  }

  def flush() = {
    stream.flush()
  }

  def close() = {
    flush()
    try {
      stream.close()
    } catch { case _ => () }
  }

  private def openWriter() = {
    val dir = new File(filename).getParentFile
    if ((dir ne null) && !dir.exists) dir.mkdirs
    new OutputStreamWriter(new FileOutputStream(filename, append), "UTF-8")
  }

  private def openLog() = {
    stream = openWriter()
    openTime = System.currentTimeMillis
    nextRollTime = computeNextRollTime()
    bytesWrittenToFile = 0
  }

  /**
   * Compute the suffix for a rolled logfile, based on the roll policy.
   */
  def timeSuffix(date: Date) = {
    val dateFormat = policy match {
      case Never => new SimpleDateFormat("yyyy")
      case Hourly => new SimpleDateFormat("yyyyMMdd-HH")
      case Daily => new SimpleDateFormat("yyyyMMdd")
      case Weekly(_) => new SimpleDateFormat("yyyyMMdd")
      case MaxSize(_) => new SimpleDateFormat("yyyyMMdd-HHmmss")
    }
    dateFormat.setCalendar(formatter.calendar)
    dateFormat.format(date)
  }

  /**
   * Return the time (in absolute milliseconds) of the next desired
   * logfile roll.
   */
  def computeNextRollTime(now: Long): Long = {
    val next = formatter.calendar.clone.asInstanceOf[Calendar]
    next.setTimeInMillis(now)
    next.set(Calendar.MILLISECOND, 0)
    next.set(Calendar.SECOND, 0)
    next.set(Calendar.MINUTE, 0)
    policy match {
      case MaxSize(_) =>
        next.add(Calendar.YEAR, 100)
      case Never =>
        next.add(Calendar.YEAR, 100)
      case Hourly =>
        next.add(Calendar.HOUR_OF_DAY, 1)
      case Daily =>
        next.set(Calendar.HOUR_OF_DAY, 0)
        next.add(Calendar.DAY_OF_MONTH, 1)
      case Weekly(weekday) =>
        next.set(Calendar.HOUR_OF_DAY, 0)
        do {
          next.add(Calendar.DAY_OF_MONTH, 1)
        } while (next.get(Calendar.DAY_OF_WEEK) != weekday)
    }
    next.getTimeInMillis
  }

  def computeNextRollTime(): Long = computeNextRollTime(System.currentTimeMillis)
  
  def maxFileSizeBytes = policy match {
    case MaxSize(sizeBytes) => sizeBytes
    case _ => Long.MaxValue
  }

  /**
   * Delete files when "too many" have accumulated.
   * This duplicates logrotate's "rotate count" option.
   */
  private def removeOldFiles(filenamePrefix: String) = {
    if (rotateCount >= 0) {
      val filesInLogDir = new File(filename).getParentFile().listFiles()
      val rotatedFiles = filesInLogDir.filter(f => f.getName != filename &&
        f.getName.startsWith(new File(filenamePrefix).getName)).sortBy(_.getName)

      val toDeleteCount = math.max(0, rotatedFiles.size - rotateCount)
      rotatedFiles.take(toDeleteCount).foreach(_.delete())
    }
  }

  def roll() = synchronized {
    stream.close()
    val n = filename.lastIndexOf('.')
    val (filenamePrefix, filenameSuffix) =
      if (n > 0) (filename.substring(0, n), filename.substring(n)) else (filename, "")

    val newFilename = filenamePrefix + "-" + timeSuffix(new Date(openTime)) + filenameSuffix
    new File(filename).renameTo(new File(newFilename))
    openLog()
    removeOldFiles(filenamePrefix)
  }

  def publish(record: javalog.LogRecord) = {
    try {
      val formattedLine = getFormatter.format(record)
      val lineSizeBytes = formattedLine.getBytes("UTF-8").length
      synchronized {
        if (System.currentTimeMillis > nextRollTime) {
          roll
        }
        if (bytesWrittenToFile + lineSizeBytes > maxFileSizeBytes) {
          roll
        }
        stream.write(formattedLine)
        stream.flush
        bytesWrittenToFile += lineSizeBytes
      }
    } catch {
      case e =>
        System.err.println(Formatter.formatStackTrace(e, 30).mkString("\n"))
    }
  }
}
