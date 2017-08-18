name := "vtdxml4s"

organization := "com.springernature"

crossScalaVersions := Seq("2.12.3", "2.11.7")

scalaVersion := crossScalaVersions.value.head

scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings")

libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-xml" % "1.0.5",
    "com.ximpleware" % "vtd-xml" % "2.13",
    "org.scalatest" %% "scalatest" % "3.0.0" % "test"
  )
