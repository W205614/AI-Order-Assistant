@echo off
setlocal
rem ============================================================
rem  AI-Order-Assistant agent-service test runner (Windows)
rem  Runs deterministic checks using the conda env "ai-order-agent".
rem ============================================================
cd /d "%~dp0"

for /f "delims=" %%B in ('conda info --base 2^>nul') do set CONDA_BASE=%%B

if "%CONDA_BASE%"=="" (
  echo [ERROR] conda not found. Please install Anaconda/Miniconda and add it to PATH.
  exit /b 1
)

set ENV_PY=%CONDA_BASE%\envs\ai-order-agent\python.exe
if not exist "%ENV_PY%" (
  echo [ERROR] conda env "ai-order-agent" not found: %ENV_PY%
  echo.
  echo Create it with one of:
  echo   conda env create -f environment.yml
  echo   conda create -n ai-order-agent python=3.13 -y ^&^& pip install -r requirements.txt
  exit /b 1
)

echo [INFO] Using conda env python: %ENV_PY%
"%ENV_PY%" -m compileall -q app tests evals
if errorlevel 1 exit /b %errorlevel%

"%ENV_PY%" -m unittest discover -s tests -v
exit /b %errorlevel%
