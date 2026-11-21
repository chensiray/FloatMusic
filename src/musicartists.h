#pragma once

#include "musicapi.h"

class MusicArtists : public QObject {
    Q_OBJECT
    Q_PROPERTY(QVariantMap state READ state NOTIFY changed)

public:
    explicit MusicArtists(QObject *parent = nullptr);
    explicit MusicArtists(const MusicApi::Endpoints &endpoints, QObject *parent = nullptr);
    QVariantMap state() const;
    QVariantMap work(const QString &id) const;
    Q_INVOKABLE QVariantList filteredWorks(const QString &query) const;
    QVariantMap workCollection(const QString &source, const QString &id, const QString &query,
                               const QString &trackId) const;
    void setSearchOptions(const QStringList &sources, int limit);
    Q_INVOKABLE void action(const QString &action, const QVariantMap &args = {});

signals:
    void changed();

private:
    void search(const QString &query, const QString &token = {});
    void open(const QString &source, const QString &id);
    void clearDetail();
    void loadAllWorks();
    void fetchWorksPage(quint64 generation);

    MusicApi m_api;
    QStringList m_sources{"netease", "tencent", "kuwo"};
    int m_limit = 30;
    QVariantList m_results;
    QVariantMap m_detail;
    QVariantList m_works;
    QSet<QString> m_workIds;
    QSet<QByteArray> m_workPages;
    QString m_searchError;
    QString m_searchToken;
    QString m_detailError, m_worksError;
    bool m_searching = false;
    bool m_profileLoading = false, m_worksLoading = false, m_hasMoreWorks = false, m_loadAllWorks = false;
    qint64 m_worksCursor = 0;
    quint64 m_searchGeneration = 0, m_detailGeneration = 0;
    QList<QPointer<QNetworkReply>> m_searchReplies;
    QPointer<QNetworkReply> m_profileReply, m_worksReply;
};
