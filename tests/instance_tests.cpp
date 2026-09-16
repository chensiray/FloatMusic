#include <QtTest>
#include <QTemporaryDir>
#include <QProcess>
#include <QTimer>
#include <cstdio>
#include "singleinstance.h"

class InstanceTests : public QObject {
    Q_OBJECT
private slots:
    void repeatedLaunchAndCrashRecovery() {
        QTemporaryDir dir; QVERIFY(dir.isValid());
        const auto exe=QCoreApplication::applicationFilePath();
        QProcess primary;primary.start(exe,{"--instance-child",dir.path()});QVERIFY(primary.waitForStarted());
        QVERIFY(primary.waitForReadyRead(5000));QVERIFY(primary.readAllStandardOutput().contains("PRIMARY"));
        for(int i=0;i<3;++i) {QProcess secondary;secondary.start(exe,{"--instance-child",dir.path()});QVERIFY(secondary.waitForFinished(5000));QCOMPARE(secondary.exitCode(),0);QVERIFY(secondary.readAllStandardOutput().contains("SECONDARY"));}
        QVERIFY(primary.state()==QProcess::Running);
        QVERIFY(primary.waitForReadyRead(2000)||primary.bytesAvailable()>0);QVERIFY(primary.readAllStandardOutput().contains("ACTIVATE"));
        primary.kill();QVERIFY(primary.waitForFinished());
        QProcess restarted;restarted.start(exe,{"--instance-child",dir.path()});QVERIFY(restarted.waitForReadyRead(5000));QVERIFY(restarted.readAllStandardOutput().contains("PRIMARY"));restarted.kill();QVERIFY(restarted.waitForFinished());
    }
};
int main(int argc,char **argv) {
    QCoreApplication app(argc,argv);
    if(app.arguments().contains("--instance-child")) {
        SingleInstance instance(app.arguments().last());int result=instance.start();
        if(result==1){printf("SECONDARY\n");fflush(stdout);return 0;}if(result!=0)return result;
        QObject::connect(&instance,&SingleInstance::activate,[]{printf("ACTIVATE\n");fflush(stdout);});
        printf("PRIMARY\n");fflush(stdout);QTimer::singleShot(10000,&app,&QCoreApplication::quit);return app.exec();
    }
    InstanceTests tests;return QTest::qExec(&tests,argc,argv);
}
#include "instance_tests.moc"
