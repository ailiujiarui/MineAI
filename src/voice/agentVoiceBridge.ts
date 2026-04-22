// @ts-nocheck

export async function applyVoiceIntent(event, intent, handlers) {
    if (!intent) {
        return null;
    }

    if (intent.kind === 'command') {
        await handlers.onCommand?.(intent.payload, event, intent);
        return intent;
    }

    if (intent.kind === 'goal') {
        await handlers.onGoal?.(intent.payload, event, intent);
        return intent;
    }

    if (intent.kind === 'companion') {
        await handlers.onCompanion?.(intent.payload, event, intent);
        return intent;
    }

    await handlers.onConversation?.(intent.payload, event, intent);
    return intent;
}
