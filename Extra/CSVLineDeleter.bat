@echo off
setlocal

:: Define the variables directly here
set "csvFileName=dd_latency_with_classififcation.csv"
set "parentDir=C:\Users\andre\Documents\HiWi\floodlight\results\with_traffic"
set "numLines=1"

:: Use PowerShell to process each CSV file in the subdirectories
for /r "%parentDir%" %%f in (%csvFileName%) do (
    echo Processing: %%f
    powershell -Command ^
        "(Get-Content '%%f' | Select-Object -Skip %numLines%) | Set-Content '%%f'"
)

echo Done processing all CSV files.
pause
