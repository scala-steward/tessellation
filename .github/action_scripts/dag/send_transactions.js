const { sendTransactions } = require( '../shared/send_transactions' );


const main = async () => {
    await sendTransactions(false)
};

main();
