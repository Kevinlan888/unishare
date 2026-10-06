namespace UniShare.Application.Models;

public enum DiagnosticComponent { App, Sdk }
public enum DiagnosticStage { SettingsLoad, SdkInitialize, SdkStart, SdkStop, StartupTask, AppLaunch }
public enum DiagnosticOutcome { Began, Succeeded, Failed }
public enum NativeDiagnosticStage
{
    Unknown, Permission, Listen, Publish, Browse, NetworkChange, Stop, Connect,
    SocketConnect, TlsHandshake, IdentityVerify, Pairing, Offer, Accept,
    TransferStream, IntegrityVerify, PeerComplete, Cancel, Transfer, Disconnect, DiagnosticQueue
}
public enum NativeDiagnosticOutcome { Unknown, Began, Succeeded, Failed, Cancelled }
public enum DiagnosticDirection { None, Send, Receive, Unknown }
public enum DiagnosticContentKind { None, Text, Image, File, Unknown }
public sealed record DiagnosticRecord(NativeDiagnosticStage Stage, NativeDiagnosticOutcome Outcome,
    DiagnosticDirection Direction, DiagnosticContentKind ContentKind, ulong Sequence,
    ShareErrorKind? Category, long? PlatformCode);
public enum TransferDiagnosticStage { Unknown, SourceOpen, CommandSubmit, DestinationPrepare, AcceptSubmit, Completion }
public sealed record TransferDiagnosticRecord(TransferDiagnosticStage Stage, NativeDiagnosticOutcome Outcome,
    DiagnosticDirection Direction, DiagnosticContentKind ContentKind, ulong Sequence,
    ShareErrorKind? Category, long? PlatformCode);
