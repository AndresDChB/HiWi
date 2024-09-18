@echo off
setlocal

:: Define the variables directly here
set "csvFileName=dd_iteration_latency.csv"
set "parentDir=C:\Users\andre\Documents\HiWi\floodlight\results\with_traffic"
set "columnNames=Resolution,Latency(s)"  :: Modify this line with your column names

:: Echo the parent directory for debugging
echo Parent Directory: %parentDir%

:: Check if the parent directory exists
if not exist "%parentDir%" (
    echo ERROR: Parent directory does not exist: %parentDir%
    goto :eof
)

:: List all files in the parent directory and its subdirectories for debugging
echo Listing all files in %parentDir%:
dir "%parentDir%"  :: This will list all files and directories for debugging

:: Process each CSV file in the subdirectories
echo Searching for files named %csvFileName% in %parentDir%...

:: Process each CSV file in the subdirectories
for /r "%parentDir%" %%f in (%csvFileName%) do (
    echo Processing File: %%f

    :: Echo the directory to check if it exists
    echo Directory: %%~dpf
    
    if not exist "%%~dpf" (
        echo ERROR: The directory does not exist: %%~dpf
        goto :eof
    )

    :: Create a temporary file to store the modified content
    set "tempFile=%%~dpftmp_%%~nf.csv"
    
    :: Echo the temporary file path for debugging
    echo Creating temp file: %tempFile%
    
    :: Echo column names to the temporary file
    echo %columnNames% > "%tempFile%"
    
    :: Append the original content of the CSV to the temporary file
    type "%%f" >> "%tempFile%"
    
    :: Replace the original file with the new file
    move /Y "%tempFile%" "%%f"
)

echo Column names added to all CSV files.
pause