ThisBuild / scalaVersion := "3.3.6"
ThisBuild / version      := "1.0.0"

lazy val root = (project in file("."))
  .enablePlugins(PlayScala)
  .settings(
    name := "realworld-play",
    libraryDependencies ++= Seq(
      filters,
      "com.typesafe.slick" %% "slick"          % "3.6.0",
      "com.typesafe.slick" %% "slick-hikaricp" % "3.6.0",
      "org.xerial"          % "sqlite-jdbc"    % "3.50.3.0"
    ),
    scalacOptions ++= Seq("-feature"),
    // No documentation artifacts in the staged release.
    Compile / doc / sources                := Seq.empty,
    Compile / packageDoc / publishArtifact := false,
    // Play writes a RUNNING_PID file by default; it is not useful for a foreground server.
    Universal / javaOptions ++= Seq(
      "-Dpidfile.path=/dev/null",
      // sqlite-jdbc loads a native library; allow it without JDK warnings.
      "-J--enable-native-access=ALL-UNNAMED",
      // Pekko still uses sun.misc.Unsafe; silence the JDK 24+ deprecation warning.
      "-J--sun-misc-unsafe-memory-access=allow"
    )
  )
