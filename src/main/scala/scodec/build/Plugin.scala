package scodec.build

import sbt._
import Keys._
import sbtrelease._
import ReleaseStateTransformations._
import ReleasePlugin._
import ReleaseKeys._
import Utilities._
import com.typesafe.sbt.osgi.SbtOsgi
import SbtOsgi._
import com.typesafe.sbt.SbtGhPages._
import com.typesafe.sbt.SbtGit._
import GitKeys._
import com.typesafe.sbt.git.GitRunner
import com.typesafe.sbt.SbtPgp.PgpKeys._
import com.typesafe.sbt.SbtSite._
import com.typesafe.tools.mima.core._
import com.typesafe.tools.mima.plugin.MimaPlugin.mimaDefaultSettings
import com.typesafe.tools.mima.plugin.MimaKeys._
import pl.project13.scala.sbt.SbtJmh._
import sbtbuildinfo.Plugin._


object ScodecBuildSettings extends AutoPlugin {

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements

  object autoImport {
    lazy val scodecModule = settingKey[String]("Name of the scodec module (should match github repository name)")
    lazy val githubHttpUrl = settingKey[String]("HTTP URL to the github repository")

    case class Contributor(name: String, githubUsername: String)
    lazy val contributors = settingKey[Seq[Contributor]]("Contributors to the project")

    lazy val rootPackage = settingKey[String]("Root package of the project")
  }
  import autoImport._

  private def keySettings = Seq(
    githubHttpUrl := "https://github.com/scodec/${scodecModule.value}/",
    contributors := Seq.empty
  )

  private def ivySettings = Seq(
    organization := "org.scodec",
    organizationHomepage := Some(new URL("http://scodec.org")),
    name := scodecModule.value,
    licenses += ("Three-clause BSD-style", url(githubHttpUrl.value + "blob/master/LICENSE")),
    unmanagedResources in Compile <++= baseDirectory map { base => (base / "NOTICE") +: (base / "LICENSE") +: ((base / "licenses") * "LICENSE_*").get },
    git.remoteRepo := "git@github.com:scodec/${scodecModule.value}.git"
  )

  private def scalaSettings = buildInfoSettings ++ Seq(
    scalaVersion := "2.11.4",
    crossScalaVersions := Seq("2.11.4", "2.10.4"),
    scalacOptions ++= Seq(
      "-feature",
      "-deprecation",
      "-unchecked",
      "-Xcheckinit",
      "-Xlint",
      "-Xverify",
      "-Yclosure-elim",
      "-Yinline"),
    scalacOptions in (Compile, doc) ++= {
      // TODO tagOrBranch is wrong when building snapshots from series branches; if version ends with snapshot, use current branch name
      val tagOrBranch = if (version.value endsWith "SNAPSHOT") "master" else ("v" + version.value)
      Seq(
        "-diagrams",
        "-groups",
        "-implicits",
        "-implicits-show-all",
        "-sourcepath", baseDirectory.value.getAbsolutePath,
        "-doc-source-url", githubHttpUrl.value + "tree/" + tagOrBranch + "€{FILE_PATH}.scala"
      )
    },
    autoAPIMappings := true,
    apiURL := Some(url(s"http://scodec.org/api/scodec-spire/${version.value}/")),
    testOptions in Test += Tests.Argument("-oD"),
    crossBuild := true,
    sourceGenerators in Compile <+= buildInfo,
    buildInfoPackage := rootPackage.value,
    buildInfoKeys := Seq[BuildInfoKey](version, scalaVersion, gitHeadCommit)
  )

  private def osgiSettings = SbtOsgi.osgiSettings ++ Seq(
    OsgiKeys.exportPackage := Seq(rootPackage.value + ".*;version=${Bundle-Version}"),
    OsgiKeys.importPackage := Seq(
      """scodec.*;version="$<range;[==,=+);$<@>>"""",
      """scala.*;version="$<range;[==,=+);$<@>>"""",
      """shapeless.*;version="$<range;[==,=+);$<@>>"""",
      "*"
    ),
    OsgiKeys.additionalHeaders := Map("-removeheaders" -> "Include-Resource,Private-Package")
  )

  private def publishingSettings = Seq(
    resolvers += "Sonatype Public" at "https://oss.sonatype.org/content/groups/public/",
    publishTo <<= version { v: String =>
      val nexus = "https://oss.sonatype.org/"
      if (v.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases" at nexus + "service/local/staging/deploy/maven2")
    },
    publishMavenStyle := true,
    publishArtifact in Test := false,
    pomIncludeRepository := { x => false },
    pomExtra := (
      <url>http://github.com/scodec/{scodecModule.value}</url>
      <scm>
        <url>git@github.com:scodec/{scodecModule.value}.git</url>
        <connection>scm:git:git@github.com:scodec/{scodecModule.value}.git</connection>
      </scm>
      <developers>
        {for (Contributor(name, username) <- contributors.value) yield
        <developer>
          <id>{username}</id>
          <name>{name}</name>
          <url>http://github.com/{username}</url>
        </developer>
        }
      </developers>
    ),
    pomPostProcess := { (node) =>
      import scala.xml._
      import scala.xml.transform._
      def stripIf(f: Node => Boolean) = new RewriteRule {
        override def transform(n: Node) =
          if (f(n)) NodeSeq.Empty else n
      }
      val stripTestScope = stripIf { n => n.label == "dependency" && (n \ "scope").text == "test" }
      new RuleTransformer(stripTestScope).transform(node)(0)
    }
  )

  private def releaseSettings = ReleasePlugin.releaseSettings ++ Seq(
    releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      publishArtifacts.copy(action = publishSignedAction),
      setNextVersion,
      commitNextVersion,
      pushChanges
    )
  )

  private lazy val publishSignedAction = { st: State =>
    val extracted = st.extract
    val ref = extracted.get(thisProjectRef)
    extracted.runAggregated(publishSigned in Global in ref, st)
  }

  private def siteSettings = site.settings ++ ghpages.settings ++ site.includeScaladoc() ++ Seq(
    git.remoteRepo := "git@github.com:scodec/scodec.github.io.git",
    GitKeys.gitBranch in GhPagesKeys.updatedRepository := Some("master"),
    SiteKeys.siteMappings ++= {
      val m = (mappings in packageDoc in Compile).value
      for((f, d) <- m) yield (f, d)
    },
    GhPagesKeys.synchLocal := {
      // Adapted from https://github.com/sbt/website/blob/4ff41b9ad8b9a3613e559429555689090cb9fa29/project/Docs.scala
      val repo = GhPagesKeys.updatedRepository.value
      val nonversioned = repo / "api" / scodecModule.value
      val versioned = nonversioned / version.value
      val git = GitKeys.gitRunner.value

      gitRemoveFiles(repo, IO.listFiles(versioned).toList, git, streams.value)
      if (!version.value.endsWith("-SNAPSHOT")) {
        val snapshotVersion = version.value + "-SNAPSHOT"
        val snapshotVersioned = nonversioned / snapshotVersion
        if (snapshotVersioned.exists)
          gitRemoveFiles(repo, IO.listFiles(snapshotVersioned).toList, git, streams.value)
      }

      val mappings =  for {
        (file, target) <- SiteKeys.siteMappings.value
      } yield (file, versioned / target)
      IO.copy(mappings)

      repo
    }
  )

  // From https://github.com/sbt/website/blob/4ff41b9ad8b9a3613e559429555689090cb9fa29/project/Docs.scala
  private def gitRemoveFiles(dir: File, files: List[File], git: GitRunner, s: TaskStreams): Unit = {
    if(!files.isEmpty)
      git(("rm" :: "-r" :: "-f" :: "--ignore-unmatch" :: files.map(_.getAbsolutePath)) :_*)(dir, s.log)
    ()
  }

  private def mimaSettings = mimaDefaultSettings ++ Seq(
    previousArtifact := previousVersion(version.value) map { pv =>
      organization.value % (normalizedName.value + "_" + scalaBinaryVersion.value) % pv
    }
  )

  private def previousVersion(currentVersion: String): Option[String] = {
    val Version = """(\d+)\.(\d+)\.(\d+).*""".r
    val Version(x, y, z) = currentVersion
    if (z == "0") None
    else Some(s"$x.$y.${z.toInt - 1}")
  }

  override def projectSettings = keySettings ++ ivySettings ++ scalaSettings ++ osgiSettings ++ publishingSettings ++ releaseSettings ++ siteSettings ++ mimaSettings
}
