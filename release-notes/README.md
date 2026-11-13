# Release notes

One file per version: `release-notes/<X.Y.Z>.md`. The release workflow copies it into the GitHub release of the tag
`vX.Y.Z` and stops if it is missing.

These notes are technical: what changed in the specification, the vectors and the libraries, and what an implementer
has to do. Say clearly whether a change is compatible with version v1 of the protocol.

## Format

```
## English

One or two sentences about the version (optional).

### Specification
- One entry per line, starting with "- ".

### Libraries
- …

### Compatibility
- …
```

Use the `###` headings that fit; at least one entry is required. Check a file with
`scripts/release-notes.sh <X.Y.Z>`. Add to the file of the next version in the same pull request as the change it
describes.
