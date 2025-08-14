
@ECHO OFF
SET WRAPPER_JAR=gradle\wrapper\gradle-wrapper.jar
IF NOT EXIST %WRAPPER_JAR% (
  echo Downloading Gradle wrapper...
  mkdir gradle\wrapper 2> NUL
  powershell -command "(New-Object System.Net.WebClient).DownloadFile('https://repo1.maven.org/maven2/org/gradle/gradle-wrapper/8.5/gradle-wrapper-8.5.jar','gradle/wrapper/gradle-wrapper.jar')"
)
java -Dorg.gradle.appname=gradlew -classpath %WRAPPER_JAR% org.gradle.wrapper.GradleWrapperMain %*
