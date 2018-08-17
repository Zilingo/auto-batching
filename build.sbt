import sbt.{Credentials, Path, Resolver}

val releaseRepository = "Strawmine Ivy Repository" at "https://repository.strawmine.com/artifactory/libs-release-local"
val snapshotRepository = "Strawmine Ivy Repository Snapshot" at "https://repository.strawmine.com/artifactory/libs-snapshot-local"

name := """auto-batching"""

organization := "com.zilingo"

version := "0.0.7"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.google.inject" % "guice" % "4.0",
  "com.typesafe.akka" %% "akka-actor" % "2.4.8",
  "com.typesafe.play" %% "play-json" % "2.5.3" % Provided withSources(),
  "org.slf4j" % "slf4j-api" % "1.7.21"
)

publishTo := {
  if (isSnapshot.value) Some(snapshotRepository)
  else Some(releaseRepository)
}

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")

scalacOptions ++= Seq(
  "-Ypatmat-exhaust-depth","off",
  "-Xfatal-warnings",
  "-deprecation",
  "-unchecked",
  "-feature",
  "-Xlint"
)

resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  releaseRepository,
  snapshotRepository
)