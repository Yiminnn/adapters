const fs = require('fs');
const path = require('path');

const BASE_DIR = '/tmp/resources';

function assertInside(base, target) {
    if (target !== base && !target.startsWith(base + path.sep)) {
        throw new Error('Path traversal detected');
    }
}

/**
 * Save a data snapshot to `/tmp/resources/<category>/<fileName>`.
 * Both the category directory and the final file must resolve inside BASE_DIR
 * (symlinks in existing path components are resolved before writing).
 * @param {string} category
 * @param {string} fileName
 * @param {string} data
 */
function saveDataSnapshot(category, fileName, data) {
    if (!category || typeof category !== 'string' || category.includes('\0')) {
        throw new Error('Category must be a non-empty string');
    }
    if (!fileName || typeof fileName !== 'string' || fileName.includes('\0')) {
        throw new Error('FileName must be a non-empty string');
    }
    if (typeof data !== 'string') {
        throw new Error('Data must be a string');
    }

    const base = path.resolve(BASE_DIR);
    const snapshotDir = path.resolve(base, category);
    const filePath = path.resolve(snapshotDir, fileName);
    if (snapshotDir === base) {
        throw new Error('Category must name a sub-directory');
    }
    assertInside(base, snapshotDir);
    assertInside(snapshotDir, filePath);

    fs.mkdirSync(snapshotDir, { recursive: true });
    const realBase = fs.realpathSync(base);
    const realDir = fs.realpathSync(snapshotDir);
    assertInside(realBase, realDir);
    const realFile = path.join(realDir, path.relative(snapshotDir, filePath));
    if (fs.existsSync(realFile) && fs.lstatSync(realFile).isSymbolicLink()) {
        throw new Error('Refusing to write through a symbolic link');
    }
    assertInside(realDir, realFile);

    try {
        fs.writeFileSync(realFile, data, 'utf8');
    } catch (error) {
        throw new Error(`Failed to write snapshot file: ${error.message}`);
    }
}

module.exports = { saveDataSnapshot };
