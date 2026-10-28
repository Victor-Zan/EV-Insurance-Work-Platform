Option Explicit
Dim shell, files, runner, command, service, exitCode, attempt
If WScript.Arguments.Count <> 3 Then WScript.Quit 2
service = WScript.Arguments(1)
If service <> "backend" And service <> "admin-web" And service <> "h5-web" Then WScript.Quit 2
Set shell = CreateObject("WScript.Shell")
Set files = CreateObject("Scripting.FileSystemObject")
runner = files.BuildPath(files.GetParentFolderName(WScript.ScriptFullName), "Run-WebsiteService.ps1")
command = Quoted(WScript.Arguments(0)) & " -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File " & Quoted(runner) & " -Service " & service & " -LogDirectory " & Quoted(WScript.Arguments(2))
' A GUI host creates a hidden console, independent of the launching terminal.
' Keep the GUI host alive when a worker receives a console interrupt. Task
' Scheduler RestartOnFailure alone did not recover this on-demand task in testing.
For attempt = 0 To 3
    On Error Resume Next
    exitCode = shell.Run(command, 0, True)
    If Err.Number <> 0 Then
        exitCode = 1
        Err.Clear
    End If
    On Error GoTo 0
    If attempt < 3 Then WScript.Sleep 60000
Next
WScript.Quit 1
Function Quoted(value)
    If InStr(value, Chr(34)) > 0 Then WScript.Quit 2
    Quoted = Chr(34) & value & Chr(34)
End Function
