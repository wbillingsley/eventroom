name := "eventroom"

organization := "com.wbillingsley"

version := "0.1-SNAPSHOT"

scalaVersion := "2.10.0"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

crossScalaVersions := Seq("2.10.0")

publishTo <<= version { (v: String) =>
  val localm = "/Users/wbillingsley/sourcecode/external/repos/mymavenrepo/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some(Resolver.file("snapshots", new File(localm + "snapshots")))
  else
    Some(Resolver.file("releases", new File(localm + "releases")))
}

parallelExecution in Test := false

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.1.1"

libraryDependencies += "play" %% "play" % "2.1.1"

libraryDependencies += "com.wbillingsley" %% "handy" % "0.4-SNAPSHOT"

libraryDependencies += "play" %% "play-test" % "2.1.1" % "test"
