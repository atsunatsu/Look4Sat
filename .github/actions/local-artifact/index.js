'use strict';
const path = require('node:path');
const fs = require('node:fs');
const {DefaultArtifactClient} = require('@actions/artifact');

async function main() {
  const name = process.env.ARTIFACT_NAME;
  const file = path.resolve(process.env.ARTIFACT_PATH || '');
  const retentionDays = Number(process.env.ARTIFACT_RETENTION_DAYS || 7);
  if (!name || !fs.statSync(file).isFile()) throw Error('Artifact name/file is missing.');
  const result = await new DefaultArtifactClient().uploadArtifact(
    name, [file], path.dirname(file), {retentionDays}
  );
  console.log('Uploaded artifact: name=' + name + ' id=' + result.id + ' bytes=' + result.size);
}

main().catch(err => {console.error(err); process.exitCode = 1});
