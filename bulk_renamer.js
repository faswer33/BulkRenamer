// bulk_renamer.js
#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const { promisify } = require('util');
const rename = promisify(fs.rename);
const stat = promisify(fs.stat);
const readdir = promisify(fs.readdir);

const COLORS = {
    green: '\x1b[92m',
    red: '\x1b[91m',
    yellow: '\x1b[93m',
    blue: '\x1b[94m',
    reset: '\x1b[0m'
};

function colorize(text, color) {
    return COLORS[color] + text + COLORS.reset;
}

async function getFiles(root, recursive, extensions, filterRegex) {
    const result = [];
    const entries = await readdir(root, { withFileTypes: true });
    for (const entry of entries) {
        const fullPath = path.join(root, entry.name);
        if (entry.isDirectory()) {
            if (recursive) {
                const sub = await getFiles(fullPath, recursive, extensions, filterRegex);
                result.push(...sub);
            }
            continue;
        }
        if (extensions && extensions.length) {
            const ext = path.extname(entry.name).slice(1).toLowerCase();
            if (!extensions.includes(ext)) continue;
        }
        if (filterRegex) {
            const re = new RegExp(filterRegex);
            if (!re.test(entry.name)) continue;
        }
        result.push(fullPath);
    }
    return result;
}

function applyTemplate(template, name, ext, num, prefix, suffix, replaceFrom, replaceTo, caseType) {
    let newName = name;
    if (replaceFrom !== undefined) {
        const re = new RegExp(replaceFrom, 'g');
        newName = newName.replace(re, replaceTo);
    }
    switch (caseType) {
        case 'lower': newName = newName.toLowerCase(); break;
        case 'upper': newName = newName.toUpperCase(); break;
        case 'title': newName = newName.replace(/\b\w/g, c => c.toUpperCase()); break;
    }
    if (prefix) newName = prefix + newName;
    if (suffix) newName = newName + suffix;

    // Replace variables
    const result = template.replace(/\{([^{}]+)\}/g, (match, key) => {
        if (key === 'name') return newName;
        if (key === 'ext') return ext;
        if (key === 'old') return name;
        if (key === 'date') return new Date().toISOString().slice(0,10);
        if (key.startsWith('num')) {
            if (key.includes(':')) {
                const fmt = key.split(':')[1];
                return require('util').format(`%${fmt}`, num);
            }
            return String(num);
        }
        return match;
    });
    return result;
}

async function main() {
    const args = require('minimist')(process.argv.slice(2), {
        string: ['t', 'p', 'e', 'f', 'prefix', 'suffix', 'replace-from', 'replace-to', 'case'],
        boolean: ['r', 'dry-run', 'v'],
        alias: { t: 'template', p: 'path', r: 'recursive', e: 'ext', f: 'filter' },
        default: { p: '.', 'dry-run': false, v: false }
    });

    const template = args.t || args.template;
    if (!template) {
        console.log(colorize('Ошибка: укажите шаблон (-t)', 'red'));
        process.exit(1);
    }

    const root = args.p || '.';
    const recursive = args.r || false;
    const extStr = args.e || '';
    const extensions = extStr ? extStr.split(',').map(s => s.trim()) : null;
    const filterRegex = args.f || '';
    const prefix = args.prefix || '';
    const suffix = args.suffix || '';
    const replaceFrom = args['replace-from'];
    const replaceTo = args['replace-to'];
    const caseType = args.case || '';
    const startNum = parseInt(args.number) || 1;
    const dryRun = args['dry-run'] || false;
    const verbose = args.v || false;

    let files;
    try {
        files = await getFiles(root, recursive, extensions, filterRegex);
    } catch (err) {
        console.log(colorize('Ошибка: ' + err.message, 'red'));
        process.exit(1);
    }

    if (files.length === 0) {
        console.log(colorize('Нет файлов.', 'yellow'));
        return;
    }

    let numCounter = startNum;
    let renamed = 0;

    for (const fullPath of files) {
        const dir = path.dirname(fullPath);
        const oldName = path.basename(fullPath);
        const ext = path.extname(oldName).slice(1);
        const nameWithoutExt = path.basename(oldName, '.' + ext);

        let newName = applyTemplate(template, nameWithoutExt, ext, numCounter, prefix, suffix, replaceFrom, replaceTo, caseType);
        numCounter++;

        if (!template.includes('{ext}') && ext) {
            newName = newName + '.' + ext;
        }

        const newPath = path.join(dir, newName);

        if (dryRun || verbose) {
            console.log(`${colorize('->', 'blue')} ${oldName} -> ${newName}`);
        }

        if (!dryRun) {
            // Conflict resolution
            try {
                await stat(newPath);
                // exists
                if (newPath !== fullPath) {
                    const base = path.basename(newName, path.extname(newName));
                    const ext2 = path.extname(newName);
                    let counter = 1;
                    let tryPath;
                    do {
                        tryPath = path.join(dir, `${base}_${counter}${ext2}`);
                        counter++;
                    } while (fs.existsSync(tryPath));
                    newPath = tryPath;
                    if (verbose) {
                        console.log(colorize(`  Конфликт, новое имя: ${path.basename(newPath)}`, 'yellow'));
                    }
                }
            } catch (err) {
                // file does not exist, ok
            }

            try {
                await rename(fullPath, newPath);
                renamed++;
            } catch (err) {
                console.log(colorize(`Ошибка при переименовании ${oldName}: ${err.message}`, 'red'));
            }
        }
    }

    if (dryRun) {
        console.log(colorize(`Симуляция завершена. Будет переименовано ${files.length} файлов.`, 'green'));
    } else {
        console.log(colorize(`Переименовано ${renamed} файлов.`, 'green'));
    }
}

main().catch(err => {
    console.log(colorize('Ошибка: ' + err.message, 'red'));
    process.exit(1);
});
