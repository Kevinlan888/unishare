using UniShare.Application.Abstractions;
using UniShare.Application.Services;
using System.Xml.Linq;

namespace UniShare.App.Services;

public static class NotificationPayload
{
    public static string BuildXml(AppNotificationRequest request, Func<string, string> localize)
    {
        // No content-bearing domain object crosses this boundary.
        var toast = new XElement("toast");
        if (request.Device is { } device) toast.SetAttributeValue("launch", NotificationArguments.Build(
            request.Request is null ? NotificationAction.Open : NotificationAction.ViewAndAccept, device, request.Request));
        toast.Add(new XElement("visual", new XElement("binding", new XAttribute("template", "ToastGeneric"),
            new XElement("text", localize("AppTitle")), new XElement("text", localize(request.BodyResourceKey)))));
        if (request.Actions.Count != 0 && request.Device is { } target && request.Request is { } id)
            toast.Add(new XElement("actions", request.Actions.Select(action => new XElement("action",
                new XAttribute("content", localize("Notification." + action)), new XAttribute("activationType", "foreground"),
                new XAttribute("arguments", NotificationArguments.Build(action, target, id))))));
        return toast.ToString(SaveOptions.DisableFormatting);
    }
}
