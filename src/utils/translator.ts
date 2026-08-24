// @ts-nocheck
import translate from 'google-translate-api-x';
import settings from '../agent/settings.js';
import { isChineseLanguage, isChineseText } from '../locale/chinese.js';



export async function handleTranslation(message, translateImpl = translate) {
    let preferred_lang = String(settings.language);
    if (!preferred_lang || preferred_lang.toLowerCase() === 'en' || preferred_lang.toLowerCase() === 'english')
        return message;
    if (isChineseLanguage(preferred_lang) && isChineseText(message))
        return message;
    try {
        const translation = await translateImpl(message, { to: preferred_lang });
        return translation.text || message;
    } catch (error) {
        console.error('Error translating message:', error);
        return message;
    }
}

export async function handleEnglishTranslation(message, translateImpl = translate) {
    let preferred_lang = String(settings.language);
    if (!preferred_lang || preferred_lang.toLowerCase() === 'en' || preferred_lang.toLowerCase() === 'english')
        return message;
    if (isChineseLanguage(settings.profile?.native_language) && isChineseText(message))
        return message;
    try {
        const translation = await translateImpl(message, { to: 'english' });
        return translation.text || message;
    } catch (error) {
        console.error('Error translating message:', error);
        return message;
    }
}

