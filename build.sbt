import Dependencies._
import com.typesafe.sbt.SbtGhPages.{ghpages, GhPagesKeys => ghkeys}
import com.typesafe.sbt.SbtGit.{git, GitKeys}
import com.typesafe.sbt.git.GitRunner
import ReleaseTransformations._

lazy val pushSiteIfChanged = taskKey[Unit]("push the site if changed")

val updateLaunchconfig = TaskKey[File]("updateLaunchconfig")

lazy val root = (project in file(".")).
  enablePlugins(BuildInfoPlugin, CrossPerProjectPlugin, PamfletPlugin).
  settings(
    updateLaunchconfig := {
      val mainClassName = (discoveredMainClasses in Compile).value match {
        case Seq(m) => m
        case zeroOrMulti => sys.error(s"could not found main class. $zeroOrMulti")
      }
      if(isSnapshot.value) {
        streams.value.log.warn(s"update launchconfig ${version.value}")
      }
      val launchconfig = s"""[app]
      |  version: ${version.value}
      |  org: ${organization.value}
      |  name: ${normalizedName.value}
      |  class: ${mainClassName}
      |[scala]
      |  version: ${scalaVersion.value}
      |[repositories]
      |  local
      |  foundweekends-maven-releases: https://dl.bintray.com/foundweekends/maven-releases/
      |  sonatype-releases: https://oss.sonatype.org/content/repositories/releases/
      |  maven-central
      |""".stripMargin
      val f = (baseDirectory in ThisBuild).value / "src/main/conscript/cs/launchconfig"
      IO.write(f, launchconfig)
      val r = GitKeys.gitRunner.value
      val s = streams.value.log
      r("add", f.getName)(baseDirectory.value, s)
      r("commit", "-m", "update " + f)(baseDirectory.value, s)
      f
    },
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      releaseStepCommandAndRemaining(s"^ plugin/scripted"),
      setReleaseVersion,
      releaseStepTask(updateLaunchconfig),
      commitReleaseVersion,
      tagRelease,
      releaseStepCommandAndRemaining(s";publish;^ plugin/publish"),
      setNextVersion,
      commitNextVersion,
      pushChanges
    ),
    scalaVersion := "2.11.8",
    inThisBuild(List(
      organization := "org.foundweekends.conscript",
      homepage := Some(url("https://github.com/foundweekends/conscript/")),
      bintrayOrganization := Some("foundweekends"),
      bintrayRepository := "maven-releases",
      bintrayReleaseOnPublish := false,
      bintrayPackage := "conscript",
      licenses := Seq("LGPL-3.0" -> url("http://www.gnu.org/licenses/lgpl.txt")),
      scalacOptions ++= Seq("-language:_", "-deprecation", "-Xlint", "-Xfuture"),
      developers := List(
        Developer("n8han", "Nathan Hamblen", "@n8han", url("http://github.com/n8han")),
        Developer("eed3si9n", "Eugene Yokota", "@eed3si9n", url("https://github.com/eed3si9n"))
      ),
      scmInfo := Some(ScmInfo(url("https://github.com/foundweekends/conscript"), "git@github.com:foundweekends/conscript.git"))
    )),
    name := "conscript",
    crossScalaVersions := List("2.11.8"),
    libraryDependencies ++= List(launcherInterface, scalaSwing, dispatchCore, scopt, liftJson, slf4jJdk14),
    bintrayPackage := (bintrayPackage in ThisBuild).value,
    bintrayRepository := (bintrayRepository in ThisBuild).value,
    mainClass in (Compile, packageBin) := Some("conscript.Conscript"),
    mappings in (Compile, packageBin) := {
      val old = (mappings in (Compile, packageBin)).value
      old filter { case (_, p) => p != "META-INF/MANIFEST.MF" }
    },
    mappings in (Compile, packageSrc) := {
      val old = (mappings in (Compile, packageSrc)).value
      old filter { case (_, p) => p != "META-INF/MANIFEST.MF" }
    },
    proguardSettings,
    ProguardKeys.proguardVersion in Proguard := "5.2.1",
    ProguardKeys.options in Proguard ++= Seq(
      "-keep class conscript.* { *; }",
      "-keep class dispatch.* { *; }",
      "-keep class com.ning.http.util.** { *; }",
      "-keep class com.ning.http.client.providers.netty.** { *; }",
      "-keep class org.apache.commons.logging.impl.LogFactoryImpl { *; }",
      "-keep class org.apache.commons.logging.impl.Jdk14Logger { *; }",
      "-dontnote",
      "-dontwarn",
      "-dontobfuscate",
      "-dontoptimize"
      ),
    ProguardKeys.inputs in Proguard := {
      ((fullClasspath in Compile).value.files ++ (fullClasspath in Runtime).value.files).distinct.filter { f =>
        // This is a dependency of the launcher interface. It may not be the version of scala
        // we're using at all, and we don't want it
        f.getName != "scala-library.jar"
      }
    },
    ProguardKeys.defaultInputFilter in Proguard := None,
    javaOptions in (Proguard, ProguardKeys.proguard) := Seq("-Xmx2G"),
    ProguardKeys.outputs in Proguard := {
      (ProguardKeys.proguardDirectory in Proguard).value / ("conscript-" + version.value + ".jar") :: Nil
    },
    artifact in (Compile, ProguardKeys.proguard) := {
      val art = (artifact in (Compile, ProguardKeys.proguard)).value
      art.copy(`classifier` = Some("proguard"))
    },
    addArtifact(artifact in (Compile, ProguardKeys.proguard), (ProguardKeys.proguard in Proguard) map { xs => xs.head }),
    buildInfoKeys := Seq(name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "conscript",
    publishMavenStyle := true,
    publishArtifact in Test := false,
    ghpages.settings,
    sourceDirectory in Pamflet := { baseDirectory.value / "docs" },
    // GitKeys.gitBranch in ghkeys.updatedRepository := Some("gh-pages"),
    // This task is responsible for updating the master branch on some temp dir.
    // On the branch there are files that was generated in some other ways such as:
    // - CNAME file
    //
    // This task's job is to call "git rm" on files and directories that this project owns
    // and then copy over the newly generated files.
    ghkeys.synchLocal := {
      // sync the generated site
      val repo = ghkeys.updatedRepository.value
      val s = streams.value
      val r = GitKeys.gitRunner.value
      gitConfig(repo, r, s.log)
      gitRemoveFiles(repo, (repo * "*.html").get.toList, r, s)
      val mappings =  for {
        (file, target) <- siteMappings.value
      } yield (file, repo / target)
      IO.copy(mappings)
      repo
    },
    pushSiteIfChanged := (Def.taskDyn {
      val repo = baseDirectory.value
      val r = GitKeys.gitRunner.value
      val s = streams.value
      val changed = gitDocsChanged(repo, r, s.log)
      if (changed) ghkeys.pushSite
      else Def.task {}
    }).value,
    git.remoteRepo := "git@github.com:foundweekends/conscript.git"
  )

lazy val plugin = (project in file("sbt-conscript")).
  enablePlugins(CrossPerProjectPlugin).
  settings(
    name := "sbt-conscript",
    sbtPlugin := true,
    bintrayOrganization := Some("sbt"),
    bintrayRepository := "sbt-plugin-releases",
    bintrayPackage := "sbt-conscript",
    crossSbtVersions := Seq("0.13.15", "1.0.0-M6"),
    ScriptedPlugin.scriptedSettings,
    ScriptedPlugin.scriptedBufferLog := false,
    // https://github.com/sbt/sbt/issues/3245
    ScriptedPlugin.scripted := {
      val args = ScriptedPlugin.asInstanceOf[{
        def scriptedParser(f: File): complete.Parser[Seq[String]]
      }].scriptedParser(sbtTestDirectory.value).parsed
      val prereq: Unit = scriptedDependencies.value
      try {
        if((sbtVersion in pluginCrossBuild).value == "1.0.0-M6") {
          ScriptedPlugin.scriptedTests.value.asInstanceOf[{
            def run(x1: File, x2: Boolean, x3: Array[String], x4: File, x5: Array[String], x6: java.util.List[File]): Unit
          }].run(
            sbtTestDirectory.value,
            scriptedBufferLog.value,
            args.toArray,
            sbtLauncher.value,
            scriptedLaunchOpts.value.toArray,
            new java.util.ArrayList()
          )
        } else {
          ScriptedPlugin.scriptedTests.value.asInstanceOf[{
            def run(x1: File, x2: Boolean, x3: Array[String], x4: File, x5: Array[String]): Unit
          }].run(
            sbtTestDirectory.value,
            scriptedBufferLog.value,
            args.toArray,
            sbtLauncher.value,
            scriptedLaunchOpts.value.toArray
          )
        }
      } catch { case e: java.lang.reflect.InvocationTargetException => throw e.getCause }
    },
    scriptedLaunchOpts ++= sys.process.javaVmArguments.filter(
      a => Seq("-Xmx", "-Xms", "-XX", "-Dsbt.log.noformat").exists(a.startsWith)
    ),
    scriptedLaunchOpts += ("-Dplugin.version=" + version.value)
  )

def gitRemoveFiles(dir: File, files: List[File], git: GitRunner, s: TaskStreams): Unit = {
  if(!files.isEmpty)
    git(("rm" :: "-r" :: "-f" :: "--ignore-unmatch" :: files.map(_.getAbsolutePath)) :_*)(dir, s.log)
  ()
}

def gitDocsChanged(dir: File, git: GitRunner, log: Logger): Boolean =
  {
    // git diff --shortstat HEAD^..HEAD docs
    val range = sys.env.get("TRAVIS_COMMIT_RANGE") match {
      case Some(x) => x
      case _       => "HEAD^..HEAD"
    }
    val stat = git(("diff" :: "--shortstat" :: range :: "docs" :: Nil) :_*)(dir, log)
    stat.trim.nonEmpty
  }

def gitConfig(dir: File, git: GitRunner, log: Logger): Unit =
  sys.env.get("TRAVIS") match {
    case Some(_) =>
      git(("config" :: "user.name" :: "Travis CI" :: Nil) :_*)(dir, log)
      git(("config" :: "user.email" :: "eed3si9n@gmail.com" :: Nil) :_*)(dir, log)
    case _           => ()
  }
