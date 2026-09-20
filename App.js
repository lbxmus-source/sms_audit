import React, { useState } from 'react';
import { Alert, Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { requireOptionalNativeModule } from 'expo';
import { StatusBar } from 'expo-status-bar';

export default function App() {
  const [error, setError] = useState('');
  const open = () => {
    if (Platform.OS !== 'android') {
      setError('Это приложение предназначено для Android.');
      return;
    }
    const audit = requireOptionalNativeModule('SmsAudit');
    if (!audit) {
      setError('Нужен APK, собранный через Expo EAS Build. Expo Go и Snack не содержат модуль чтения SMS и офлайн-перевода.');
      return;
    }
    try { audit.open(); } catch (_) { Alert.alert('Не удалось открыть проверку', 'Закройте приложение и откройте его снова.'); }
  };
  return (
    <View style={styles.screen}>
      <StatusBar style="dark" />
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.badge}>ИВРИТ → РУССКИЙ</Text>
        <Text style={styles.title}>Проверка SMS</Text>
        <Text style={styles.text}>Переведите SMS на телефоне и сохраните отчёт для разбора в чате.</Text>
        <View style={styles.card}>
          <Text style={styles.step}>1. Загрузите офлайн-модели</Text>
          <Text style={styles.step}>2. Разрешите чтение SMS</Text>
          <Text style={styles.step}>3. Запустите проверку</Text>
          <Text style={styles.step}>4. Сохраните отчёт и загрузите в чат</Text>
        </View>
        <Pressable accessibilityRole="button" onPress={open} style={styles.button}>
          <Text style={styles.buttonText}>Открыть проверку</Text>
        </Pressable>
        {!!error && <Text accessibilityRole="alert" style={styles.error}>{error}</Text>}
        <Text style={styles.note}>Автоматическая проверка отмечает подозрительные места. Правильность смысла разбирается по отчёту. После загрузки моделей перевод работает без интернета.</Text>
      </ScrollView>
    </View>
  );
}
const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#eef4fc' },
  content: { padding: 24, paddingTop: 70, paddingBottom: 40 },
  badge: { color: '#2266bb', fontWeight: '700', letterSpacing: 2, fontSize: 12 },
  title: { color: '#192a40', fontSize: 36, fontWeight: '700', marginTop: 16 },
  text: { color: '#394b62', fontSize: 18, lineHeight: 27, marginTop: 18 },
  card: { backgroundColor: '#fff', borderRadius: 18, padding: 20, marginVertical: 28 },
  step: { fontSize: 16, color: '#192a40', lineHeight: 30 },
  button: { backgroundColor: '#2266bb', padding: 18, borderRadius: 14, alignItems: 'center' },
  buttonText: { color: '#fff', fontWeight: '700', fontSize: 18 },
  note: { color: '#52647a', fontSize: 14, lineHeight: 22, marginTop: 22 },
  error: { color: '#a02a26', fontSize: 15, lineHeight: 23, marginTop: 18 },
});
