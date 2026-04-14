I'm walking away — take this through to production on alpha-gke autonomously. Follow every step in order, fixing issues as they arise:

## Step 1: Create PR
- Create a PR against `unstoppabledomains/nomulus` (base: `master`) using `gh pr create`
- Follow the project's PR template and conventions
- If a PR already exists for this branch, skip to Step 2

## Step 2: Wait for CI
- Monitor the PR's CI checks using `gh pr checks` — only the `-gke` suffixed checks matter
- Poll every 2-3 minutes until checks complete
- If checks are still pending after 30 minutes, alert the user

## Step 3: Fix CI failures
- If any `-gke` check fails, investigate the failure using `gh run view`
- Fix the issue, commit, and push
- Return to Step 2 to wait for the new checks

## Step 4: Squash merge
- Once all `-gke` checks are green, squash merge using `gh pr merge --squash`
- If merge is blocked by branch protection, use `--admin` to override
- Verify the merge completed successfully

## Step 5: Deploy to alpha-gke
- Trigger the "Build and Deploy GCP Applications" GitHub Action on the `unstoppabledomains/nomulus-secrets` repo (you don't need a local clone — `gh` can target it remotely)
- Use: `gh workflow run "Build and Deploy GCP Applications" --repo unstoppabledomains/nomulus-secrets -f environment=alpha -f application=nomulus`
- If the workflow name or parameters differ, check with: `gh workflow list --repo unstoppabledomains/nomulus-secrets` and `gh workflow view <id> --repo unstoppabledomains/nomulus-secrets`

## Step 6: Monitor deployment
- Find the triggered run with: `gh run list --repo unstoppabledomains/nomulus-secrets --workflow="Build and Deploy GCP Applications" -L 1`
- Monitor it with `gh run watch <run-id> --repo unstoppabledomains/nomulus-secrets` or poll `gh run view` every 2-3 minutes
- Report final status (success/failure) with the run URL

## Important notes
- Do NOT ask for confirmation at any step — proceed autonomously
- If something truly unexpected happens (e.g., merge conflicts, infra errors), stop and explain the situation
- Mark each step complete as you go so progress is visible
