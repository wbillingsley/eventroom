name := "eventroom"

organization := "com.wbillingsley"

version := "0.1-SNAPSHOT"

scalaVersion := "2.10.2"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

crossScalaVersions := Seq("2.10.2")

parallelExecution in Test := false

resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.1.1"

libraryDependencies += "com.typesafe.play" %% "play" % "2.2.1"

libraryDependencies += "com.wbillingsley" %% "handy" % "0.5-SNAPSHOT"

libraryDependencies += "com.wbillingsley" %% "handy-play" % "0.5-SNAPSHOT"

libraryDependencies += "com.typesafe.play" %% "play-test" % "2.2.1" % "test"


licenses in ThisBuild := Seq("MIT License" -> url("http://www.opensource.org/licenses/mit-license.php"))

homepage in ThisBuild := Some(url("http://github.com/wbillingsley/eventroom"))

publishMavenStyle in ThisBuild := true

publishTo in ThisBuild <<= version { (v: String) =>
  val nexus = "https://oss.sonatype.org/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

parallelExecution in Test := false

pomExtra in ThisBuild := (
  <scm>
    <url>git@github.com:wbillingsley/eventroom.git</url>
    <connection>scm:git:git@github.com:wbillingsley/eventroom.git</connection>
  </scm>
  <developers>
    <developer>
      <id>wbillingsley</id>
      <name>William Billingsley</name>
      <url>http://www.wbillingsley.com</url>
    </developer>
  </developers>
)