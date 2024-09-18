@echo off
setlocal

:: Define the variables directly here
set "csvFileName=dd_latency_with_classififcation.csv"
set "parentDir=C:\Users\andre\Documents\HiWi\floodlight\results\with_traffic"
set "newFileName=dd_latency_with_classification.csv"

:: Process each CSV file in the subdirectories
for /r "%parentDir%" %%f in (%csvFileName%) do (
    echo Renaming: %%f
    set "newPath=%%~dpf%newFileName%"
    echo New path: %newPath%
    ren "%%f" "%newFileName%"
)

echo Done renaming all CSV files.
pause