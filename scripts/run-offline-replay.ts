import process from 'node:process';
if (!(globalThis as any).File) {
    (globalThis as any).File = class File extends Blob {
        name: string;
        lastModified: number;
        constructor(parts: BlobPart[], name: string, options: FilePropertyBag = {}) {
            super(parts, options);
            this.name = name;
            this.lastModified = options.lastModified || Date.now();
        }
    };
}

const { runOfflineReplayFile } = await import('../src/agent/execution/offlineReplay.js');

const file = process.argv[2];
if (!file) {
    console.error('Usage: npm run replay -- <case.json>');
    process.exitCode = 2;
} else {
    try {
        const report = await runOfflineReplayFile(file);
        console.log(JSON.stringify(report, null, 2));
        if (!report.passed) process.exitCode = 1;
    } catch (error: any) {
        console.error(error?.message || error);
        process.exitCode = 1;
    }
}
