// Small, dependency-free long-term memory store for NPC facts and relationships.
const MAX_RECORDS = 200;

function now() { return Date.now(); }

function normalize(value: unknown) {
    return String(value ?? '').trim().toLowerCase();
}

function terms(value: unknown) {
    const text = normalize(value);
    const words = text.match(/[a-z0-9_]+|[\u3400-\u9fff]{2}/g) || [];
    return words.length > 0 ? words : (text ? [text] : []);
}

export class StructuredMemory {
    facts: Array<{ key: string; value: string; source: string; updatedAt: number }> = [];
    events: Array<{ text: string; speakerId: string | null; createdAt: number }> = [];
    places: Array<{ name: string; position: unknown; updatedAt: number }> = [];
    tasks: Array<{ id: string; status: string; summary: string; updatedAt: number }> = [];
    relations: Record<string, { interactions: number; trust: number; lastSeenAt: number; preferences: string[] }> = {};

    rememberFact(key: string, value: string, source = 'conversation') {
        const normalizedKey = normalize(key);
        if (!normalizedKey || !String(value).trim()) return;
        const record = { key: normalizedKey, value: String(value).trim(), source, updatedAt: now() };
        const index = this.facts.findIndex((fact) => fact.key === normalizedKey);
        if (index >= 0) this.facts[index] = record;
        else this.facts.push(record);
        this.trim();
    }

    rememberEvent(text: string, speakerId: string | null = null) {
        if (!String(text).trim()) return;
        this.events.push({ text: String(text).trim(), speakerId, createdAt: now() });
        this.trim();
    }

    rememberPlace(name: string, position: unknown) {
        const index = this.places.findIndex((place) => normalize(place.name) === normalize(name));
        const record = { name: String(name).trim(), position, updatedAt: now() };
        if (index >= 0) this.places[index] = record;
        else this.places.push(record);
        this.trim();
    }

    updateTask(id: string, status: string, summary: string) {
        const index = this.tasks.findIndex((task) => task.id === id);
        const record = { id, status, summary, updatedAt: now() };
        if (index >= 0) this.tasks[index] = record;
        else this.tasks.push(record);
        this.trim();
    }

    noteInteraction(speakerId: string) {
        const id = String(speakerId || '').trim();
        if (!id) return;
        const relation = this.relations[id] || { interactions: 0, trust: 0, lastSeenAt: 0, preferences: [] };
        relation.interactions += 1;
        relation.lastSeenAt = now();
        relation.trust = Math.min(100, relation.trust + 1);
        this.relations[id] = relation;
    }

    addPreference(speakerId: string, preference: string) {
        const id = String(speakerId || '').trim();
        if (!id || !String(preference).trim()) return;
        const relation = this.relations[id] || { interactions: 0, trust: 0, lastSeenAt: 0, preferences: [] };
        if (!relation.preferences.includes(preference.trim())) relation.preferences.push(preference.trim());
        this.relations[id] = relation;
    }

    retrieve(query = '', speakerId: string | null = null) {
        const queryTerms = terms(query);
        const matches = (text: string) => queryTerms.length === 0 || queryTerms.some((term) => normalize(text).includes(term));
        return {
            facts: this.facts.filter((fact) => matches(`${fact.key} ${fact.value}`)).slice(-20),
            events: this.events.filter((event) => matches(event.text)).slice(-20),
            places: this.places.filter((place) => matches(place.name)).slice(-20),
            tasks: this.tasks.filter((task) => matches(`${task.id} ${task.status} ${task.summary}`)).slice(-20),
            relation: speakerId ? this.relations[speakerId] || null : null
        };
    }

    toJSON() {
        return { facts: this.facts, events: this.events, places: this.places, tasks: this.tasks, relations: this.relations };
    }

    load(value: any) {
        if (!value || typeof value !== 'object') return;
        this.facts = Array.isArray(value.facts) ? value.facts : [];
        this.events = Array.isArray(value.events) ? value.events : [];
        this.places = Array.isArray(value.places) ? value.places : [];
        this.tasks = Array.isArray(value.tasks) ? value.tasks : [];
        this.relations = value.relations && typeof value.relations === 'object' ? value.relations : {};
        this.trim();
    }

    clear() {
        this.facts = []; this.events = []; this.places = []; this.tasks = []; this.relations = {};
    }

    private trim() {
        this.facts = this.facts.slice(-MAX_RECORDS);
        this.events = this.events.slice(-MAX_RECORDS);
        this.places = this.places.slice(-MAX_RECORDS);
        this.tasks = this.tasks.slice(-MAX_RECORDS);
    }
}
