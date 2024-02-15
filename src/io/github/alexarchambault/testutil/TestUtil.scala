package io.github.alexarchambault.testutil

import java.io.PrintStream
import java.io.IOException
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Promise
import scala.util.Success
import scala.util.Failure
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object TestUtil {

  implicit class OsTempOps(private val temp: os.temp.type) extends AnyVal {
    def withDir[T](
      errorOutput: PrintStream = System.err,
      cleanUp: Boolean = true
    )(f: os.Path => T): T = {
      val dir  = os.temp.dir(deleteOnExit = cleanUp)
      val dir0 = os.Path(dir.toIO.getCanonicalFile)
      try f(dir0)
      finally
        if (cleanUp)
          try os.remove.all(dir0)
          catch {
            case e: IOException =>
              errorOutput.println(
                s"Ignoring exception while removing temporary directory $dir0: $e"
              )
              e.printStackTrace(errorOutput)
              errorOutput.flush()
          }
    }
    def withContent[T](
      content: Seq[(os.SubPath, os.Source)],
      cleanUp: Boolean = true,
      errorOutput: PrintStream = System.err
    )(f: os.Path => T): T =
      withDir(errorOutput, cleanUp) { tmpDir =>
        for ((relPath, content0) <- content)
          os.write(tmpDir / relPath, content0, createFolders = true)
        f(tmpDir)
      }
  }

  implicit class ProcOps(private val proc: os.proc) extends AnyVal {
    def withSubProcess[T](
      cwd: os.Path = null,
      env: Map[String, String] = null,
      stdin: os.ProcessInput = os.Pipe,
      stdout: os.ProcessOutput = os.Pipe,
      stderr: os.ProcessOutput = os.Inherit,
      mergeErrIntoOut: Boolean = false,
      propagateEnv: Boolean = true,
      timeout: Option[FiniteDuration] = Some(2.minutes),
      processShouldExit: Boolean = true,
      errorOutput: PrintStream = System.err
    )(f: (os.SubProcess, () => Unit) => T): T = {
      val subProc = proc.spawn(
        cwd = cwd,
        env = env,
        stdin = stdin,
        stdout = stdout,
        stderr = stderr,
        mergeErrIntoOut = mergeErrIntoOut,
        propagateEnv = propagateEnv
      )

      val ignoreSubprocessExit = new AtomicBoolean(false)
      val watchProcessPromise  = Promise[T]()
      val watchProcessThread: Thread = new Thread("test-watch-sub-process") {
        setDaemon(true)
        override def run(): Unit =
          try {
            val exitCode = subProc.waitFor()

            if (!ignoreSubprocessExit.get()) {
              errorOutput.println(s"Server exited with code $exitCode")
              // User actions might have made the sub-process exit on purpose, give the user thread
              // a bit of time to return before we report an error here
              errorOutput.println("Waiting for the test to finish")
              errorOutput.flush()
              Thread.sleep(5000L)

              if (!ignoreSubprocessExit.get()) {
                errorOutput.println("Sub-process exited, maybe failing the test")
                errorOutput.flush()
                watchProcessPromise.tryComplete(Failure(new Exception("Sub-process exited")))
              }
            }
          }
          catch {
            case _: InterruptedException =>
              watchProcessPromise.tryComplete(Failure(new TimeoutException))
          }
      }
      watchProcessThread.start()

      val userResultPromise = Promise[T]()
      val userThread: Thread = new Thread("test") {
        setDaemon(true)
        override def run(): Unit = {
          val res =
            try {
              val res = f(
                subProc,
                () => {
                  errorOutput.println("Ignoring sub-process exit")
                  errorOutput.flush()
                  ignoreSubprocessExit.set(true)
                }
              )
              if (processShouldExit) {
                errorOutput.println(s"Waiting for sub-process ${subProc.wrapped.pid()} to exit")
                errorOutput.flush()
                subProc.waitFor()
              }
              Success(res)
            }
            catch {
              case e: Throwable =>
                Failure(e)
            }
          userResultPromise.complete(res)
        }
      }
      userThread.start()

      val futures = Seq(
        userResultPromise.future,
        watchProcessPromise.future
      )
      val future = Future.firstCompletedOf(futures)(ExecutionContext.global)

      try
        Await.result(future, timeout.getOrElse(Duration.Inf))
      finally {
        subProc.close()
        if (subProc.isAlive() && !subProc.waitFor(200L))
          subProc.destroyForcibly()
      }
    }
  }

  def daemonThreadFactory(namePrefix: String): ThreadFactory = {

    val threadNumber = new AtomicInteger(1)

    new ThreadFactory {
      def newThread(r: Runnable) = {
        val threadNumber0 = threadNumber.getAndIncrement()
        val t             = new Thread(r, s"$namePrefix-thread-$threadNumber0")
        t.setDaemon(true)
        t.setPriority(Thread.NORM_PRIORITY)
        t
      }
    }
  }

  def fixedThreadPool(namePrefix: String, size: Int): ExecutorService = {

    val factory = daemonThreadFactory(namePrefix)

    // 1 min keep alive
    val executor = new ThreadPoolExecutor(
      size,
      size,
      1L,
      TimeUnit.MINUTES,
      new LinkedBlockingQueue[Runnable],
      factory
    )
    executor.allowCoreThreadTimeOut(true)
    executor
  }

  def fixedScheduledThreadPool(namePrefix: String, size: Int): ScheduledExecutorService = {

    val factory = daemonThreadFactory(namePrefix)

    val executor = new ScheduledThreadPoolExecutor(size, factory)
    executor.setKeepAliveTime(1L, TimeUnit.MINUTES)
    executor.allowCoreThreadTimeOut(true)
    executor
  }

  lazy val outputFramePool = fixedScheduledThreadPool("test-output", 1)
  val enableOutputFrame =
    // System.console() != null && // ignored for now, seems Mill always runs tests without a console
    System.getenv("CI") == null

  def runWithTimeout[T](timeout: Option[FiniteDuration])(f: => T): T = {

    val donePromise = Promise[T]()

    val userThread: Thread =
      new Thread("test") {
        setDaemon(true)
        override def run() = {
          val maybeValue =
            try Success(f)
            catch {
              case e: Throwable =>
                Failure(e)
            }
          donePromise.complete(maybeValue)
        }
      }

    timeout match {
      case None => f
      case Some(timeout0) =>
        userThread.start()
        Await.result(donePromise.future, timeout0)
    }
  }

  implicit class PPrintOnOutputStreamOption(private val osOpt: Option[OutputStream])
      extends AnyVal {
    def pprint[T](x: sourcecode.Text[T])(implicit
      line: sourcecode.Line,
      fileName: sourcecode.FileName
    ): T =
      osOpt match {
        case Some(os) => os.pprint(x)
        case None     => _root_.pprint.err.log(x)
      }
  }
  implicit class PPrintOnOutputStream(private val os: OutputStream) extends AnyVal {
    def pprint[T](x: sourcecode.Text[T])(implicit
      line: sourcecode.Line,
      fileName: sourcecode.FileName
    ): T = {

      // copied and adapted from pprint.PPrinter.doLog

      val prefix = Seq(
        fansi.Color.Magenta(fileName.value),
        fansi.Str(":"),
        fansi.Color.Green(line.value.toString),
        fansi.Str(" "),
        fansi.Color.Cyan(x.source),
        fansi.Str(": ")
      )
      val str = fansi.Str.join(
        prefix ++
          _root_.pprint.tokenize(
            x.value,
            _root_.pprint.defaultWidth,
            _root_.pprint.defaultHeight,
            _root_.pprint.defaultIndent,
            escapeUnicode = _root_.pprint.defaultEscapeUnicode,
            showFieldNames = _root_.pprint.defaultShowFieldNames
          ).toSeq
      )

      os.write((str.toString + System.lineSeparator()).getBytes(StandardCharsets.UTF_8))
      x.value
    }
  }

}
