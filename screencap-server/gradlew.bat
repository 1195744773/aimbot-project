@if "%DEBUG%"=="" @echo ON
@setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set JAVA_EXE=java.exe
"%JAVA_EXE%" "-Dorg.gradle.appname=gradlew" -classpath "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
if errorlevel 1 exit /b 1
@endlocal