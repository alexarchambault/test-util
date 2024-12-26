package io.github.alexarchambault.testutil

import io.github.alexarchambault.testutil.TestUtil.*
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

object ProcessTest {

  def apply[T](
    proc: os.proc,
    timeout: Option[FiniteDuration] = Some(1.minute),
    count: Int = 1,
    env: Map[String, String] = Map.empty,
    runProcIn: os.Path => os.Path = identity,
    enableOutputFrame: Boolean = true,
    enableSilentOutput: Boolean = true,
    printOutputOnError: Boolean = true,
    cleanUp: Boolean = true,
    newOutputFrame: () => OutputFrame = () => new OutputFrame()
  )(
    content: (os.SubPath, os.Source)*
  )(f: (os.Path, os.SubProcess, () => Unit, TestOutput, Int) => T): T = {

    val output = new TestOutput(
      enableOutputFrame,
      enableSilentOutput,
      newOutputFrame = newOutputFrame
    )

    var success = false
    output.start()
    try
      os.temp.withContent(content, cleanUp, errorOutput = output.printStream) { tmpDir =>
        os.makeDir.all(tmpDir)

        def run(runCount: Int): T =
          proc.withSubProcess(
            cwd = runProcIn(tmpDir),
            env = env,
            timeout = timeout.map(_ * 2),
            stderr = output.processOutput,
            errorOutput = output.printStream
          ) { (subProc, ignoreSubprocExit) =>
            runWithTimeout(timeout) {
              f(tmpDir, subProc, ignoreSubprocExit, output, runCount)
            }
          }

        for (i <- 0 until (count - 1))
          run(i)
        val value = run(count - 1)
        success = true
        value
      }
    finally
      output.close(success, printOutputOnError)
  }

}
