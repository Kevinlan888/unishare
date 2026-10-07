# UniShare package signing

`UniShare.Package.cer` is the public certificate that users can install to trust internal UniShare packages. It contains no private key and is safe to keep in Git.

The matching private key is stored in the current Windows user's `My` certificate store. Its thumbprint is recorded in `package-signing-thumbprint.txt`, allowing every worktree on this computer to use the same key after branches are merged.

Build a signed package from the `windows` directory:

```powershell
.\scripts\package.ps1
```

The resolver rejects missing, expired, public-only, wrong-subject, and non-code-signing certificates. To use a PFX supplied by a CI secret instead, pass `-CertificatePath` and `-CertificatePassword`. Never commit a PFX, PEM, or private-key file.

On another computer, importing `UniShare.Package.cer` establishes trust for packages signed on the signing computer, but does not grant signing access.
