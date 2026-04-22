// @ts-nocheck

export function formatStatCoordinate(value) {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
        return 'unknown';
    }
    return value.toFixed(2);
}
