# Doliparr Android – Dolibarr 20 için Android yardımcı uygulaması

## 1) Dolibarr tarafı (bir kez)
1. Giriş > Ayarlar > Modüller: **"Web hizmetleri API REST (geliştirici)"** modülünü etkinleştirin.
2. Kullanıcılar & Gruplar > kendi kullanıcınız > **API anahtarı** üretin (kaydedin).
3. Kullanıcıya Ürünler (okuma/oluşturma/değiştirme), Stok (okuma/hareket) ve Belgeler yetkilerini verin.
4. PC tarayıcısında test: `http://localhost/dolibarr/api/index.php/explorer`
5. Windows güvenlik duvarı: Apache (80) ve röle (8765) için **özel ağ** erişimine izin verin.
6. Kasa PC'ye sabit IP verin (modem arayüzünden DHCP rezervasyonu).

## 2) Kasa PC – barkod rölesi
`pc-relay/relay.ps1` dosyasında `$Token` değerini değiştirin, çalıştırın:
`powershell -ExecutionPolicy Bypass -File relay.ps1`
Otomatik açılması için dosyaya kısayol oluşturup `shell:startup` klasörüne koyun.
TakePOS'ta arama kutusu seçili olmalı (barkod okuyucu gibi çalışır).

## 3) Uygulamayı derleme
Android Studio (Koala veya yeni) ile `Doliparr-Android` klasörünü açın, Gradle sync, telefonu USB ile bağlayıp (Geliştirici seçenekleri > USB hata ayıklama) Run.
APK için: Build > Build APK(s).

## 4) Telefonda Ayarlar sekmesi
Dolibarr adresi (örn. http://192.168.1.20/dolibarr), API anahtarı, Depo ID, kasa PC IP'si, röle gizli kelimesi.

## Android Studio olmadan APK (GitHub ile, ücretsiz)
1. github.com'da ücretsiz hesap açın, **Private** yeni bir depo (repository) oluşturun.
2. Bu klasörün İÇİNDEKİLERİ (app, .github, build.gradle.kts ... ) depoya yükleyin ("Add file > Upload files").
   `.github` klasörü gizli görünebilir; depoda `.github/workflows/build.yml` yolunun oluştuğundan emin olun.
3. Depoda **Actions > APK olustur > Run workflow** deyin. 5-10 dk sonra bitince çalıştırmanın sayfasından **Doliparr-Android-apk** dosyasını indirin (zip içinde app-debug.apk).
4. APK'yı telefona aktarıp açın ("bilinmeyen kaynaklardan yüklemeye izin ver" gerekir).

## Sorun giderme
- **HTTP 403 (ürünler/tara):** Dolibarr'da API anahtarının sahibi kullanıcıda Ürünler ve Stok okuma/değiştirme yetkisi yok. Kullanıcı kartı > İzinler bölümünden verin (en kolayı: kullanıcıyı yönetici yapın).
- **PC'ye gönderilemedi (zaman aşımı):** relay çalışmıyor ya da Windows güvenlik duvarı 8765 portunu engelliyor. `pc-relay/guvenlik-duvari-izni.bat` dosyasını yönetici olarak çalıştırın, sonra `baslat.bat` ile röleyi açın.

- **Relay "403 / gizli kelime uyuşmuyor":** telefonda Ayarlar'daki "Röle gizli kelimesi" ile `relay.ps1` içindeki `$Token` birebir aynı olmalı (harf/rakam, büyük-küçük harf önemli). Röle penceresinde reddedilen istekte iki değer de yazılır.
- **Ürün listesinde fotoğraf yerine ⚠:** Dolibarr kullanıcısında Belgeler/Ürün okuma yetkisi eksik olabilir.

## Rölenin otomatik başlaması
`pc-relay/otomatik-baslat-kur.bat` dosyasını bir kez çalıştırın: röle her Windows oturumunda gizli pencerede kendiliğinden başlar.
Kaldırmak/durdurmak için `durdur-ve-kaldir.bat`. Gizli kelime `token.txt` dosyasındadır.

## Güncelleme notu (v2)
- Röle artık görev çubuğunda yer kaplamaz; sistem tepsisinde mavi "D" simgesi olarak çalışır (sağ tık > Çıkış).
- Uygulamada üst menü (☰), ana sayfa, satış sepeti, yeni ürün formunda açıklama/depo/KDV/kategori.
