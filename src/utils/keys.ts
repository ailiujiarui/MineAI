// @ts-nocheck
import 'dotenv/config';
import { readFileSync } from 'fs';

let keys = {};
try {
    const data = readFileSync('./keys.json', 'utf8');
    keys = JSON.parse(data);
} catch (err) {
    console.warn('keys.json not found. Using .env or process environment values.'); // still works with local models
}

export function getKey(name) {
    const key = process.env[name] || keys[name];
    if (!key) {
        throw new Error(`API key "${name}" not found in .env, process environment, or keys.json!`);
    }
    return key;
}

export function hasKey(name) {
    return process.env[name] || keys[name];
}

