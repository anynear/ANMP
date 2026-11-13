# Security

ANMP protects messages, files and passwords of real people. If you find a weakness in the specification or in the code
of this repository, please tell us before you make it public.

## Reporting

Write to **support@anynear.app**, in English or German. Describe what you observed and how it can be reproduced.
Please do not include other people's data, invitation links or backup files, and do not open a public issue for a
vulnerability.

- We confirm receipt and tell you what happens next.
- Please give us time to fix the problem before you publish it.
- Test only with your own devices, circles and keys, and do not disrupt the relays of the anynear network.
- We cannot pay a bounty. If you wish, we name you once the problem is fixed.

## Scope

The specification (`spec/anmp.md`, `spec/anmp.cddl`), the test vectors and the Kotlin and Go code in this repository.
For the apps and the website see https://anynear.app/en/technology/.

## Known limits

Version v1 has no forward secrecy, treats the invitation link of a circle as its key, and does not sign direct packets
over radio. The section "Security considerations" of `spec/anmp.md` lists these and other limits. They are not
vulnerabilities to report, but comments on them are welcome.
