# Pully CI runners

On 28 October 2026, four repository-scoped Linux runners were installed on
`mini-002-netcup`: `pully-linux-1` through `pully-linux-4`. GitHub assigns Pully
Linux jobs using the `pully-linux` label. iOS verification uses GitHub-hosted
macOS and is optional for merging and release publication.

The runners run as `pullygha`, a dedicated account without sudo or Docker group
membership. Their installations and workspaces are in `/opt/actions-runner-pully-1`
through `/opt/actions-runner-pully-4`. Each service has its own home and Actions
tool cache under `/home/pullygha/runner-N`; the Rust toolchain is shared under
`/home/pullygha/.rustup` and `/home/pullygha/.cargo`. The enabled services are named
`actions.runner.OctavianTocan-Pully.pully-linux-N.service` for N from 1 through 4.

Check their registration and service status on the server:

```sh
gh api repos/OctavianTocan/Pully/actions/runners --jq '.runners[] | {name,status,busy}'
systemctl is-active actions.runner.OctavianTocan-Pully.pully-linux-{1,2,3,4}.service
```

These persistent runners share a host and account. GitHub jobs can retain files
between runs. The repository is public; Octavian configured pull-request creation
for collaborators only. Review access and workflows before expanding collaborators
or allowing outside pull requests, because jobs execute repository code on this host.

The repository was renamed from `pulls.review` to `Pully`; release admission uses
the new canonical name. Deployment checkouts must use
`https://github.com/OctavianTocan/Pully.git` as their origin when the updated release
policy is promoted to `release`.
