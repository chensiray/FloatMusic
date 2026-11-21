#include "musicartists.h"
#include <QRegularExpression>
#include <algorithm>
#include <utility>

namespace {
void cancel(QList<QPointer<QNetworkReply>> &replies) {
    const auto pending = std::exchange(replies, {});
    for (const auto &reply : pending) {
        if (reply && !reply->isFinished()) reply->abort();
    }
}
}

MusicArtists::MusicArtists(QObject *parent) : MusicArtists(MusicApi::builtinEndpoints(), parent) {}

MusicArtists::MusicArtists(const MusicApi::Endpoints &endpoints, QObject *parent)
    : QObject(parent), m_api(endpoints) {}

QVariantMap MusicArtists::state() const {
    auto detail = m_detail;
    if (!detail.isEmpty()) {
        detail["tracks"] = m_works;
        detail["hasMore"] = m_hasMoreWorks;
    }
    return {{"results", m_results}, {"detail", detail}, {"searching", m_searching},
            {"loading", m_profileLoading || (m_worksLoading && m_works.isEmpty())},
            {"worksLoading", m_worksLoading}, {"worksError", m_worksError},
            {"searchError", m_searchError}, {"searchToken", m_searchToken}, {"detailError", m_detailError}};
}

QVariantMap MusicArtists::work(const QString &id) const {
    for (const auto &entry : m_works) {
        const auto track = entry.toMap();
        if (track.value("id").toString() == id) return track;
    }
    return {};
}

QVariantList MusicArtists::filteredWorks(const QString &query) const {
    static const QRegularExpression whitespace("\\s+", QRegularExpression::UseUnicodePropertiesOption);
    const auto terms = query.left(200).toLower().split(whitespace, Qt::SkipEmptyParts);
    if (terms.isEmpty()) return m_works;
    QVariantList result;
    for (const auto &entry : m_works) {
        const auto track = entry.toMap();
        const auto text = (track.value("name").toString() + '\n' + track.value("artist").toString()
                          + '\n' + track.value("album").toString()).toLower();
        if (std::all_of(terms.cbegin(), terms.cend(), [&text](const auto &term) { return text.contains(term); }))
            result.append(entry);
    }
    return result;
}

QVariantMap MusicArtists::workCollection(const QString &source, const QString &id, const QString &query,
                                         const QString &trackId) const {
    if (id.isEmpty() || m_detail.value("source").toString() != source || m_detail.value("id").toString() != id)
        return {};
    QVariantList tracks;
    if (!trackId.isEmpty()) {
        const auto selected = work(trackId);
        if (!selected.isEmpty()) tracks.append(selected);
    } else if (!m_hasMoreWorks && !m_worksLoading) {
        tracks = filteredWorks(query);
    }
    if (tracks.isEmpty()) return {};
    return {{"name", m_detail.value("name")}, {"description", m_detail.value("description")}, {"tracks", tracks}};
}

void MusicArtists::setSearchOptions(const QStringList &sources, int limit) {
    m_limit = limit;
    if (sources == m_sources) return;
    m_sources = sources;
    search({});
}

void MusicArtists::action(const QString &action, const QVariantMap &args) {
    if (action == "search") search(args.value("query").toString(), args.value("searchToken").toString());
    else if (action == "open") open(args.value("source").toString(), args.value("id").toString());
    else if (action == "loadAll") loadAllWorks();
    else if (action == "back") {
        clearDetail();
        emit changed();
    } else if (action == "close") search({});
}

void MusicArtists::search(const QString &query, const QString &token) {
    const auto generation = ++m_searchGeneration;
    cancel(m_searchReplies);
    clearDetail();
    m_results.clear();
    m_searchError.clear();
    m_searchToken = token.left(64);
    const auto keywords = query.trimmed().left(200);
    m_searching = !keywords.isEmpty() && !m_sources.isEmpty();
    if (!keywords.isEmpty() && m_sources.isEmpty())
        m_searchError = QStringLiteral("请至少选择一个曲库后搜索。");
    emit changed();
    if (!m_searching || generation != m_searchGeneration) return;

    const auto replies = m_api.searchArtists(keywords, m_sources, m_limit,
        [this, generation](QVariantList rows, QString error) {
            if (generation != m_searchGeneration) return;
            m_searchReplies.clear();
            m_results = std::move(rows);
            m_searchError = std::move(error);
            m_searching = false;
            emit changed();
        });
    if (generation == m_searchGeneration && m_searching) m_searchReplies = replies;
}

void MusicArtists::open(const QString &source, const QString &id) {
    QVariantMap selected;
    for (const auto &entry : std::as_const(m_results)) {
        const auto artist = entry.toMap();
        if (artist.value("source").toString() == source && artist.value("id").toString() == id) {
            selected = artist;
            break;
        }
    }
    if (selected.isEmpty()) {
        clearDetail();
        m_detailError = QStringLiteral("歌手资料未能加载。");
        emit changed();
        return;
    }

    clearDetail();
    const auto generation = m_detailGeneration;
    m_detail = std::move(selected);
    m_hasMoreWorks = true;
    m_profileLoading = true;
    m_worksLoading = true;
    emit changed();
    if (generation != m_detailGeneration) return;

    auto *profile = m_api.fetchArtistProfile(source, id, [this, generation](QString description, QString error) {
        if (generation != m_detailGeneration) return;
        if (!description.isEmpty()) m_detail["description"] = description;
        if (!error.isEmpty()) m_detailError = QStringLiteral("歌手简介暂不可用。");
        m_profileReply.clear();
        m_profileLoading = false;
        emit changed();
    });
    if (generation != m_detailGeneration) return;
    if (m_profileLoading) m_profileReply = profile;
    fetchWorksPage(generation);
}

void MusicArtists::clearDetail() {
    ++m_detailGeneration;
    if (auto reply = std::exchange(m_profileReply, {})) reply->abort();
    if (auto reply = std::exchange(m_worksReply, {})) reply->abort();
    m_detail.clear();
    m_works.clear();
    m_workIds.clear();
    m_workPages.clear();
    m_detailError.clear();
    m_worksError.clear();
    m_profileLoading = m_worksLoading = m_hasMoreWorks = m_loadAllWorks = false;
    m_worksCursor = 0;
}

void MusicArtists::loadAllWorks() {
    if (m_detail.isEmpty() || !m_hasMoreWorks) return;
    m_loadAllWorks = true;
    if (m_worksLoading) return;
    m_worksError.clear();
    m_worksLoading = true;
    const auto generation = m_detailGeneration;
    emit changed();
    if (generation == m_detailGeneration) fetchWorksPage(generation);
}

void MusicArtists::fetchWorksPage(quint64 generation) {
    auto *reply = m_api.fetchArtistWorks(m_detail.value("source").toString(), m_detail.value("id").toString(),
        m_worksCursor, [this, generation](MusicApi::ArtistWorksPage page, const QString &error) {
            if (generation != m_detailGeneration) return;
            m_worksReply.clear();
            if (!error.isEmpty() || (!page.signature.isEmpty() && m_workPages.contains(page.signature))) {
                m_worksError = QStringLiteral("部分歌手作品未能加载，可重试。");
                m_worksLoading = false;
                emit changed();
                return;
            }
            if (!page.signature.isEmpty()) m_workPages.insert(page.signature);
            for (const auto &entry : page.tracks) {
                const auto id = entry.toMap().value("id").toString();
                if (m_workIds.contains(id)) continue;
                m_workIds.insert(id);
                m_works.append(entry);
            }
            m_worksCursor = page.nextCursor;
            m_hasMoreWorks = page.hasMore;
            m_worksLoading = m_loadAllWorks && m_hasMoreWorks;
            emit changed();
            if (generation == m_detailGeneration && m_worksLoading && !m_worksReply) fetchWorksPage(generation);
        });
    if (generation == m_detailGeneration && m_worksLoading) m_worksReply = reply;
}
