name := "vtdxml4s"

organization := "com.springernature"

crossScalaVersions := Seq("2.12.3", "2.11.7", "2.13.1")

version:= "1.0.7"

scalaVersion := crossScalaVersions.value.head

scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings")

libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-xml" % "1.3.0",
    "com.ximpleware" % "vtd-xml" % "2.13.4",
    "org.scalatest" %% "scalatest" % "3.1.0" % "test"
  )

licenses ++= Seq(("GPL-2.0", url("http://opensource.org/licenses/GPL-2.0")))
bintrayOrganization := Some("springernature")
bintrayRepository := "vtdxml4s"
bintrayReleaseOnPublish in ThisBuild := false
