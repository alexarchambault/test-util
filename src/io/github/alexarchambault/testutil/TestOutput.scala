package io.github.alexarchambault.testutil

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.ScheduledExecutorService
import java.io.IOException

class TestOutput(
  enableOutputFrame: Boolean,
  enableSilentOutput: Boolean
) extends AutoCloseable {

  val baos = new ByteArrayOutputStream

  val outputFrameOpt =
    if (enableSilentOutput && enableOutputFrame)
      Some(new OutputFrame())
    else
      None
  val outputStreamOpt: Option[OutputStream] =
    outputFrameOpt.map(_.outputStream).orElse {
      if (enableSilentOutput) Some(baos)
      else None
    }

  lazy val printStream = outputFrameOpt.map(_.printStream)
    .orElse(outputStreamOpt.map(new PrintStream(_)))
    .getOrElse(System.err)

  lazy val processOutput: os.ProcessOutput =
    outputStreamOpt
      .map(TestOutput.FixedReadBytes.pipeTo(_))
      .getOrElse(os.Inherit)

  def start(outputFramePool: ScheduledExecutorService = TestOutput.defaultOutputFramePool): Unit =
    outputFrameOpt.foreach(_.start(outputFramePool))

  def close(success: Boolean, printOutputOnError: Boolean): Unit = {
    close()
    if (!success) {
      outputStreamOpt.foreach(_.flush())
      if (printOutputOnError) {
        System.err.write(baos.toByteArray)
        System.err.flush()
      }
    }
  }

  def close(): Unit = {
    outputFrameOpt.foreach(_.stop(keepFrame = false, errored = None))
  }

}

object TestOutput {
  lazy val defaultOutputFramePool = TestUtil.fixedScheduledThreadPool("test-output", 1)

  // Same as os.ProcessOutput.ReadBytes, but for exception handling in the Runnable
  case class FixedReadBytes(f: (Array[Byte], Int) => Unit) extends os.ProcessOutput {
    def redirectTo = ProcessBuilder.Redirect.PIPE
    def processOutput(out: => os.SubProcess.OutputStream): Option[Runnable] =
      Some {
        new Runnable {
          def run(): Unit =
            try os.Internals.transfer0(out, f)
            catch {
              case e: IOException if e.getMessage == "Stream closed" =>
              // ignored
            }
        }
      }
  }

  object FixedReadBytes {
    def pipeTo(outputStream: OutputStream): FixedReadBytes =
      FixedReadBytes((b, len) => outputStream.write(b, 0, len))
    def pipeTo(outputStreamOpt: Option[OutputStream]): os.ProcessOutput =
      outputStreamOpt.fold[os.ProcessOutput](os.Inherit)(pipeTo(_))
  }
}
