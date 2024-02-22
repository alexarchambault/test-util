package io.github.alexarchambault.testutil

// adapted from https://github.com/coursier/coursier/blob/f8c2fc9724af1a02227fadf7844fd0ce0c73495f/modules/publish/src/main/scala/coursier/publish/logging/OutputFrame.scala

import coursier.cache.internal.ConsoleDim
import coursier.cache.internal.Terminal.Ansi

import java.io.*
import java.util.concurrent.{ScheduledExecutorService, ScheduledFuture}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.io.OutputStreamWriter

/** Displays the last `height` lines in the terminal via `out`, updating them along time */
final class OutputFrame(
  out: Writer = new OutputStreamWriter(System.err),
  height: Int = math.min(10, OutputFrame.maxHeight()),
  preamble: Seq[String] = Seq("--- Test is running ---"),
  postamble: Seq[String] = Nil
) {

  private val lock = new Object
  def addLine(line: String): Unit =
    lock.synchronized {
      lines.append(line)
      inCaseOfErrorLines.append(line)
    }

  private[this] val lines              = new OutputFrame.Lines(height)
  private[this] val inCaseOfErrorLines = new OutputFrame.Lines(100) // don't hard-code size?

  private def clear(): Unit = {

    var count = 0

    for (_ <- preamble) {
      out.clearLine(2)
      out.write('\n')
      count += 1
    }

    var n = 0
    while (n < height + 1) {
      out.clearLine(2)
      out.write('\n')
      n += 1
    }

    count += n

    for (_ <- postamble) {
      out.clearLine(2)
      out.write('\n')
      count += 1
    }

    out.up(count)

    out.flush()
  }

  private var currentFirst: OutputFrame.Line = null
  private var currentWidth: Int              = 0
  private def updateOutput(scrollUp: Boolean = true): Runnable =
    new Runnable {
      def run() = {
        val (it, newFirst) = lines.linesIteratorAndFirstElem()
        val width          = ConsoleDim.width()

        if ((newFirst ne currentFirst) || width != currentWidth) {
          currentFirst = newFirst
          currentWidth = width

          var count = 0

          for (l <- preamble) {
            val l0 =
              if (preamble.length <= width) l
              else l.substring(0, width)
            out.clearLine(2)
            out.write(l0 + System.lineSeparator())
            count += 1
          }

          var n = 0
          while (n < height && it.hasNext) {
            val l = it.next()
              // https://stackoverflow.com/a/25189932/3714539
              .replaceAll("\u001B\\[[\\d;]*[^\\d;]", "")
            val l0 =
              if (l.length <= width) l
              else l.substring(0, width)
            out.clearLine(2)
            out.write(l0 + System.lineSeparator())
            n += 1
          }

          while (n < height + 1) {
            out.clearLine(2)
            out.write('\n')
            n += 1
          }

          count += n

          for (l <- postamble) {
            val l0 =
              if (preamble.length <= width) l
              else l.substring(0, width)
            out.clearLine(2)
            out.write(l0 + System.lineSeparator())
            count += 1
          }

          if (scrollUp)
            out.up(count)

          out.flush()
        }
      }
    }

  private var updateFutureOpt = Option.empty[ScheduledFuture[_]]

  def start(pool: ScheduledExecutorService, period: FiniteDuration = 20.milliseconds): Unit = {
    assert(updateFutureOpt.isEmpty)
    updateFutureOpt = Some {
      pool.scheduleAtFixedRate(updateOutput(), 0L, period.length, period.unit)
    }
  }

  def stop(keepFrame: Boolean = true, errored: Option[PrintStream] = None): Unit =
    for (updateFuture <- updateFutureOpt) {
      updateFuture.cancel(false)
      updateFutureOpt = None
      errored match {
        case None =>
          if (keepFrame)
            updateOutput(scrollUp = false).run()
          else
            clear()
        case Some(errStream) =>
          inCaseOfErrorLines.linesIterator().foreach(errStream.println)
      }
    }

  lazy val baos = new ByteArrayOutputStream
  lazy val outputStream: OutputStream =
    new OutputStream {
      val decoder        = StandardCharsets.UTF_8.newDecoder()
      val lock           = new Object
      val byteArray      = Array.ofDim[Byte](256 * 1024)
      val buffer         = ByteBuffer.wrap(byteArray)
      val charArray      = Array.ofDim[Char](256 * 1024)
      val charBuffer     = CharBuffer.wrap(charArray)
      def processBuffer(): Unit = {
        val formerPos = buffer.position()
        buffer.position(0)
        val buffer0 = buffer.slice()
        buffer0.limit(buffer.position())
        buffer.position(formerPos)
        decoder.decode(buffer0, charBuffer, false)
        if (buffer0.position() > 0) {
          val unread = buffer.position() - buffer0.position()
          assert(
            unread >= 0,
            s"buffer.position=${buffer.position()}, buffer0.position=${buffer0.position()}"
          )
          System.arraycopy(byteArray, buffer0.position(), byteArray, 0, unread)
          buffer.position(0)
        }
        def processLines(startIdx: Int): Int = {
          val nlIdxOpt = (startIdx until charBuffer.position()).find { idx =>
            try
              charBuffer.get(idx) == '\n'
            catch {
              case e: IndexOutOfBoundsException =>
                throw new Exception(
                  s"idx=$idx, startIdx=$startIdx, charBuffer.position=${charBuffer.position()}",
                  e
                )
            }
          }
          nlIdxOpt match {
            case Some(nlIdx) =>
              val line = new String(charArray, startIdx, nlIdx - startIdx).stripSuffix("\r")
              addLine(line)
              processLines(nlIdx + 1)
            case None =>
              startIdx
          }
        }
        val remainingIdx = processLines(0)
        try System.arraycopy(
            charArray,
            remainingIdx,
            charArray,
            0,
            charBuffer.position() - remainingIdx
          )
        catch {
          case e: ArrayIndexOutOfBoundsException =>
            throw new Exception(
              s"remainingIdx=$remainingIdx, charBuffer.position=${charBuffer.position()}",
              e
            )
        }
        charBuffer.position(charBuffer.position() - remainingIdx)
      }
      override def write(b: Int) = lock.synchronized {
        baos.write(b)
        buffer.put(b.toByte)
        processBuffer()
      }
      override def write(b: Array[Byte], off: Int, len: Int) = lock.synchronized {
        baos.write(b, off, len)
        buffer.put(b, off, len)
        processBuffer()
      }
      override def flush() =
        baos.flush()
    }
  lazy val printStream: PrintStream =
    new PrintStream(outputStream)
  def output(): Array[Byte] =
    baos.toByteArray
}

object OutputFrame {

  def maxHeight(): Int =
    ConsoleDim.height()

  private final class Lines(height: Int) {

    def firstLine = first

    @volatile private[this] var first: Line = _
    @volatile private[this] var last: Line  = _

    // should not be called multiple times in parallel
    def append(value: String): Unit = {
      assert(value ne null)
      val index = if (last eq null) 0L else last.index + 1L
      val l     = new Line(value, index)

      if (last eq null) {
        assert(first eq null)
        first = l
        last = l
      }
      else {
        last.setNext(l)
        last = l
      }

      while (last.index - first.index > height) {
        // Let the former `first` be garbage-collected, if / once no more `linesIterator` reference it.
        first = first.next
        assert(first ne null)
      }
    }

    // thread safe, and can be used while append gets called
    def linesIterator(): Iterator[String] = {
      val (it, _) = linesIteratorAndFirstElem()
      it
    }

    // thread safe, and can be used while append gets called
    def linesIteratorAndFirstElem(): (Iterator[String], Line) = {
      val first0 = first
      val it: Iterator[String] =
        new Iterator[String] {
          var current = first0
          def hasNext = current ne null
          def next() = {
            val v = current.value
            current = current.next
            v
          }
        }
      (it, first0)
    }

  }

  private final class Line(val value: String, val index: Long) {

    @volatile private[this] var next0: Line = _

    def setNext(next: Line): Unit = {
      assert(this.next0 eq null)
      assert(!(next eq null))
      this.next0 = next
    }

    def next: Line =
      next0
  }

}
