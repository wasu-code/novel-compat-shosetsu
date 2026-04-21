#!/bin/bash
set -e

cat ../main/repo/index.json
cat ./index.json
rsync -avv --checksum --delete --exclude .git --exclude .gitignore --exclude README.md --exclude repo.json ../main/repo/ .
cat ../main/repo/index.json
cat ./index.json
git config --global user.email "github-actions[bot]@users.noreply.github.com"
git config --global user.name "github-actions[bot]"
git status
if [ -n "$(git status --porcelain)" ]; then
    git add .
    git commit -m "Update extensions repo"
    git push
else
    echo "No changes to commit"
fi
