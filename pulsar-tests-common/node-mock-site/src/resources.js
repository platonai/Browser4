import {promises as fs} from 'fs';
import path from 'path';
import {fileURLToPath} from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Candidate relative paths (from repo root) for the amazon html pages
const CANDIDATE_BASES = [
  'pulsar-core/pulsar-resources/src/test/resources/pages/amazon', // location observed in tests
  'pulsar-tests-common/src/main/resources/pages/amazon',           // future possible location
  'src/main/resources/pages/amazon'                                // generic fallback
];

async function findFirstExisting(fileName) {
  // Walk upward from current directory to locate repo root by presence of VERSION or pom.xml
  let dir = path.resolve(__dirname, '../../..'); // start a few levels up (node-mock-site root's parent)
  const maxDepth = 6;
  let repoRoot = null;
  for (let i = 0; i < maxDepth; i++) {
    try {
      const versionStat = await fs.stat(path.join(dir, 'VERSION')).catch(() => null);
      const pomStat = await fs.stat(path.join(dir, 'pom.xml')).catch(() => null);
      if (versionStat || pomStat) {
        repoRoot = dir;
        break;
      }
    } catch {_err}
    dir = path.dirname(dir);
  }
  if (!repoRoot) {
    repoRoot = path.resolve(__dirname, '../../..');
  }

  for (const base of CANDIDATE_BASES) {
    const full = path.join(repoRoot, base, fileName);
    try {
      await fs.access(full);
      return full;
    } catch { /* continue */ }
  }
  throw new Error(`Resource ${fileName} not found in any candidate base paths`);
}

async function readFileMaybe(fileName) {
  const full = await findFirstExisting(fileName);
  return fs.readFile(full, 'utf8');
}

export async function readAmazonHome() {
  return readFileMaybe('home.htm');
}

export async function readAmazonProduct() {
  // The Kotlin version references B08PP5MSVB.original.htm; if missing we throw and caller provides fallback
  return readFileMaybe('B08PP5MSVB.original.htm');
}

