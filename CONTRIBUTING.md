# Contributing

Thank you for looking at ANMP. Issues and pull requests are welcome, in English or German.

## Rules

- **Specification first.** A change of the format changes `spec/anmp.md`, `spec/anmp.cddl`, the test vectors and the
  implementations in the same pull request. Kotlin writes the vectors, Go checks them; a change to the radio way
  starts with the vector.
- **Version v1 constants never change:** the magic bytes, the HKDF salt, the infos `anmp/v1/…`, field numbers and
  kinds. Readers skip unknown fields. An incompatible change is `anmp/v2/…` next to v1.
- **No home-made cryptography**, and no secrets or personal data in code, tests or vectors. Vectors use invented
  inputs and fixed nonces; real packets always use random nonces.
- Report vulnerabilities as described in `SECURITY.md`, not in an issue.

## Build and test

See `README.md`. The Kotlin core needs the repository `anynear/ANDP` checked out next to this one. Continuous
integration runs the jobs `test`, `go` and `ios` on every pull request; all must pass.

## How a pull request gets in

This repository is published from the project's working repository; nobody pushes to `main` here directly. When a pull
request is accepted, the change is applied in the working repository with you as co-author (`Co-authored-by`) and
arrives here with the next update of `main`. The pull request is then closed with a link to that commit.

## Licence and Contributor License Agreement

Contributions need your agreement to the Contributor License Agreement in [CLA.md](CLA.md): you keep the copyright and
give the project a licence that also allows it to license your contribution under other terms. A bot asks for your
agreement in your first pull request; you agree by commenting the sentence it shows. Accepted contributions stay
available under GPL-3.0-or-later, like the rest of the repository. Because of this agreement, the project can also offer the code
under a commercial licence ([COMMERCIAL.md](COMMERCIAL.md)). The name “anynear” and its logos are not covered by any
of these licences.
