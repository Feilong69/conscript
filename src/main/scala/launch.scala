package conscript

import dispatch._
import java.io.File
import util.control.Exception._

trait Launch extends Credentials {
  import Conscript.http
  import scala.concurrent.ExecutionContext.Implicits.global

  val sbtlaunchalias = "sbt-launch.jar"
  val sbtLauncherVersion = "1.0.0"

  def launchJar(display: Display): Either[String, String] =
      configdir(s"launcher-$sbtLauncherVersion.jar") match {
    case jar if jar.exists => Right("Launcher already up to date, fetching next...")
    case jar =>
      try {
        display.info("Fetching launcher...")
        val launchalias = configdir(sbtlaunchalias)
        if (!launchalias.getParentFile.exists) mkdir(launchalias)
        else ()

        val req = url(s"https://oss.sonatype.org/content/repositories/public/org/scala-sbt/launcher/$sbtLauncherVersion/launcher-$sbtLauncherVersion.jar")

        val f = http(req > as.File(jar))
        f()
        windows map { _ =>
          if (launchalias.exists) launchalias.delete
          else ()
          // should copy the one we already downloaded, but I don't
          // have a windows box to test any changes
          val f = http(req > as.File(launchalias))
          f()
        } getOrElse {
          val rt = Runtime.getRuntime
          rt.exec("ln -sf %s %s" format (jar, launchalias)).waitFor
        }
        Right("Fetching Conscript...")
      } catch {
        case e: Exception => 
          Left("Error downloading sbt launcher %s: %s".format(
            sbtLauncherVersion, e.toString
          ))
      }
  }

  implicit def str2paths(a: String) = new {
    def / (b: String) = a + File.separatorChar + b
  }
  def forceslash(a: String) =
    windows map { _ =>
      "/" + (a replaceAll ("""\\""", """/"""))
    } getOrElse {a}
  def configdir(path: String) = homedir(".conscript" / path)
  def homedir(path: String) = new File(System.getProperty("user.home"), path)
  def mkdir(file: File) =
    catching(classOf[SecurityException]).either {
      new File(file.getParent).mkdirs()
    }.left.toOption.map { e => "Unable to create path " + file }
}
