
organization := "de.aquanauten"
scalaVersion := "2.12.2"
version      := "0.1.0"
name := "scala-insights"
crossScalaVersions := Seq(scalaVersion.value,"2.11.11")
libraryDependencies ++= Seq (
  "org.scala-lang" % "scala-compiler" % "2.12.2" % "provided"
)
