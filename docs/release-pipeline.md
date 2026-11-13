# P-01: release pipeline

Added 14 November 2026. Production-engineering item P-01 (gate E05) of the [go-live plan](go-live-plan-2026-11-13.md). Exit criterion: a release candidate is promoted by the pipeline, with recorded approvals.

## Release candidates (`.github/workflows/release.yml`)

Pushing a tag `vX.Y.Z-rc.N` runs, in one job and in this order:

1. **Base image check.** The job refuses a runtime base image that is not pinned by `@sha256` digest. It is taken from the repository variable `JAVA_RUNTIME_IMAGE`.
2. **Full verification.** Ops tests, UI tests, and `mvn verify` with the PostgreSQL integration tests, the same gates as every push.
3. **Application SBOM.** CycloneDX, generated from the resolved Maven tree.
4. **Image build** from the JAR that was just verified, with OCI source, revision and version labels.
5. **Image SBOM.** SPDX, from the built image (Syft).
6. **Vulnerability scan** with Trivy. The release fails on any fixable HIGH or CRITICAL finding, and the scan report is kept.
7. **Push** to `ghcr.io/<repository>`, recording the digest.
8. **Keyless signing** with cosign through the workflow's OIDC identity. The signature is recorded in the public transparency log.
9. **Attestations:** SLSA build provenance and the SBOM, both attached to the image digest.
10. **GitHub pre-release** carrying the JAR, both SBOMs, the scan report, the image digest and `SHA256SUMS`.

## Promotion (`.github/workflows/promote.yml`)

Started by hand with the candidate tag. The job runs in the **`production` environment**. Configure that environment to require reviewers, so each promotion waits for an approval that GitHub records with who approved it and when. The job then:

1. checks the tag names;
2. verifies the candidate image's cosign signature, which must come from `release.yml` at that candidate tag;
3. verifies its provenance attestation;
4. adds the final tag `vX.Y.Z` to **the same digest**, without rebuilding;
5. tags the same commit;
6. publishes the final release with the candidate's evidence, after checking `SHA256SUMS`.

## Repository settings required (owner action)

These are GitHub settings, not code, so they need the repository owner:

- **Repository variable `JAVA_RUNTIME_IMAGE`:** a reviewed Java 21 runtime pinned by digest, for example `eclipse-temurin:21-jre-noble@sha256:…`.
- **Environment `production`:** set required reviewers. Optionally prevent self-review and restrict deployment to protected tags.
- **Tag protection** for `v*`, so only maintainers can create release tags.
- **Packages:** allow GitHub Actions to write packages for this repository. The GHCR image visibility stays private unless the bank decides otherwise.

## Deploying a release

Deploy by digest. Set `CBS_APP_IMAGE=ghcr.io/<repository>@sha256:…` from the release's `cbs-image.digest`. To check an image before deploying:

```sh
cosign verify ghcr.io/<repository>@sha256:<digest> \
  --certificate-identity-regexp '^https://github.com/<repository>/.github/workflows/release.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
gh attestation verify oci://ghcr.io/<repository>@sha256:<digest> --repo <repository>
```

## Tests

`ops/tests/test_release_workflow.py` fails if a release gate is removed or reordered:

- verification runs before the build;
- the base image must be digest-pinned;
- the image is scanned before it is pushed, and signed and attested after;
- promotion requires the `production` environment, verifies before tagging, and never rebuilds.

The workflows themselves only run on GitHub, so the first real candidate tag is the end-to-end proof.
