#pragma once
#include <QQmlNetworkAccessManagerFactory>
#include <QNetworkAccessManager>
#include <QNetworkDiskCache>
#include <QStandardPaths>
class ArtNetworkFactory final:public QQmlNetworkAccessManagerFactory {
public:
    QNetworkAccessManager *create(QObject *parent) override {
        auto *manager=new QNetworkAccessManager(parent);
        auto *cache=new QNetworkDiskCache(manager);
        cache->setCacheDirectory(QStandardPaths::writableLocation(QStandardPaths::CacheLocation)+"/covers");
        cache->setMaximumCacheSize(32*1024*1024);manager->setCache(cache);return manager;
    }
};
