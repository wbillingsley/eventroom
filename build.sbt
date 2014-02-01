name := "eventroom"

organization := "com.wbillingsley"

version := "0.1.0-RC1"

scalaVersion := "2.10.3"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

crossScalaVersions := Seq("2.10.3")

parallelExecution in Test := false

resolvers += "bintrayW" at "http://dl.bintray.com/wbillingsley/maven"

resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.1.1"

libraryDependencies += "com.typesafe.play" %% "play" % "2.2.1"

libraryDependencies += "com.wbillingsley" %% "handy" % "0.5.0-RC1"

libraryDependencies += "com.wbillingsley" %% "handy-play" % "0.5.0-RC1"

libraryDependencies += "com.typesafe.play" %% "play-test" % "2.2.1" % "test"


licenses in ThisBuild := Seq("MIT" -> url("http://www.opensource.org/licenses/mit-license.php"))

homepage in ThisBuild := Some(url("http://github.com/wbillingsley/eventroom"))

publishMavenStyle in ThisBuild := true

// Bintray settings for publishing releases
seq(bintrayPublishSettings:_*)

publishTo in ThisBuild := {
  val nexus = "https://oss.sonatype.org/"
  if (version.value.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    publishTo.value
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