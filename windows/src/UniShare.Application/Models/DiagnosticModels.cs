namespace UniShare.Application.Models;

public enum DiagnosticComponent { App, Sdk }
public enum DiagnosticStage { SettingsLoad, SdkInitialize, SdkStart, SdkStop, StartupTask, AppLaunch }
public enum DiagnosticOutcome { Began, Succeeded, Failed }
