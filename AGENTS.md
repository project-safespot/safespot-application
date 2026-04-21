# AGENTS.md

## Project
SafeSpot disaster evacuation service.

## General rules
- Do not change unrelated files.
- Prefer minimal, reviewable diffs.
- Keep naming consistent with existing docs and Terraform module layout.
- Ask for a plan first on ambiguous or multi-step tasks.
- Do not apply destructive infrastructure changes without explicit confirmation in the thread.

## Git rules
- Work only on the current branch/worktree.
- Never force-push.
- Do not rewrite history.

## Terraform rules
- Follow the module structure under `terraform/modules` and `terraform/environments`.
- Keep variables, outputs, README, and examples aligned.
- Prefer explicit inputs/outputs over hidden coupling.
- Avoid over-broad IAM permissions.
- Flag cost-sensitive resources explicitly: NAT Gateway, RDS Multi-AZ, Redis replicas, public LB.

## Review rules
- Reviewers should not make broad refactors.
- Reviewers should focus on correctness, safety, cost, security, and consistency.
- When possible, comment on diffs rather than rewriting the implementation.
