#!/usr/bin/env node

/**
 * GitHub Gist updater script
 * Called by GitHub Actions to update the flash counter Gist
 */

const { Octokit } = require('@octokit/rest');

async function updateGist() {
    const gistId = process.env.GIST_ID;
    const githubToken = process.env.GITHUB_TOKEN;
    const counterData = process.env.COUNTER_DATA;

    if (!gistId || !githubToken || !counterData) {
        console.error('Missing required environment variables');
        process.exit(1);
    }

    const octokit = new Octokit({
        auth: githubToken
    });

    try {
        const data = JSON.parse(counterData);

        // Update Gist
        await octokit.gists.update({
            gist_id: gistId,
            files: {
                'flash-counter.json': {
                    content: JSON.stringify(data, null, 2)
                }
            }
        });

        console.log('✅ Gist updated successfully');
    } catch (error) {
        console.error('❌ Failed to update Gist:', error);
        process.exit(1);
    }
}

updateGist();